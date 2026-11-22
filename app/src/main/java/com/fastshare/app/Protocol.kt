package com.fastshare.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import javax.net.ssl.SSLSocket

const val PORT = 53317
const val CHUNK = 256 * 1024
const val MAX_FRAME = 64 * 1024

class ProtoException(msg: String) : Exception(msg)
class DeclinedException : Exception("Receiver ne file mana kar di")

/** Frame = 4-byte big-endian length + UTF-8 JSON. Same as the Linux app. */
object Frames {
    fun write(out: DataOutputStream, obj: JSONObject) {
        val b = obj.toString().toByteArray(Charsets.UTF_8)
        if (b.size > MAX_FRAME) throw ProtoException("message too big")
        out.writeInt(b.size)
        out.write(b)
        out.flush()
    }

    fun read(inp: DataInputStream): JSONObject {
        val n = inp.readInt()
        if (n < 0 || n > MAX_FRAME) throw ProtoException("bad frame")
        val b = ByteArray(n)
        inp.readFully(b)
        return try {
            JSONObject(String(b, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ProtoException("bad frame")
        }
    }
}

class Me(val id: String, val alias: String)

class Conn(val sock: SSLSocket, val inp: DataInputStream, val out: DataOutputStream, val peerFp: String) {
    fun close() {
        try {
            sock.close()
        } catch (_: Exception) {
        }
    }
}

object Client {

    fun connect(ip: String, port: Int): Conn {
        val raw = Socket()
        raw.connect(InetSocketAddress(ip, port), 10_000)
        val ssl = Crypto.clientContext.socketFactory.createSocket(raw, ip, port, true) as SSLSocket
        try {
            ssl.soTimeout = 60_000
            ssl.startHandshake()
            val fp = sha256Hex(ssl.session.peerCertificates[0].encoded)
            return Conn(
                ssl,
                DataInputStream(BufferedInputStream(ssl.inputStream)),
                DataOutputStream(BufferedOutputStream(ssl.outputStream)),
                fp
            )
        } catch (e: Exception) {
            ssl.close()
            throw e
        }
    }

    fun pair(ip: String, port: Int, code: String, me: Me, myFp: String): Peer {
        val c = connect(ip, port)
        try {
            val hello = Frames.read(c.inp)
            if (hello.optString("op") != "hello") throw ProtoException("unexpected reply from device")
            Frames.write(
                c.out,
                JSONObject()
                    .put("op", "pair")
                    .put("id", me.id)
                    .put("alias", me.alias)
                    .put("fp_c", myFp)
                    .put("proof", Crypto.pairProof(code, myFp, c.peerFp))
            )
            val resp = Frames.read(c.inp)
            if (!resp.optBoolean("ok")) throw ProtoException(resp.optString("err", "Pairing failed"))
            return Peer(
                id = hello.getString("id"),
                alias = hello.optString("alias", "Device"),
                fp = c.peerFp,
                key = Crypto.pairKey(code, myFp, c.peerFp).toHex()
            )
        } finally {
            c.close()
        }
    }

    /** Connect and prove we know the pairing key (mutual). Caller must close the returned Conn. */
    fun auth(ip: String, port: Int, me: Me, peer: Peer): Conn {
        val c = connect(ip, port)
        try {
            val hello = Frames.read(c.inp)
            if (hello.optString("op") != "hello" || hello.optString("id") != peer.id) {
                throw ProtoException("device changed, pair again")
            }
            val key = peer.key.hexToBytes()
            val nonce = hello.getString("nonce")
            Frames.write(
                c.out,
                JSONObject().put("op", "auth").put("id", me.id).put("mac", Crypto.tag(key, nonce, "C", peer.id))
            )
            val resp = Frames.read(c.inp)
            if (!resp.optBoolean("ok") ||
                !Crypto.safeEq(resp.optString("mac"), Crypto.tag(key, nonce, "S", me.id))
            ) {
                throw ProtoException(resp.optString("err", "Not paired / auth failed"))
            }
            return c
        } catch (e: Throwable) {
            c.close()
            throw e
        }
    }

    fun sendText(ip: String, port: Int, me: Me, peer: Peer, mid: String, text: String, ts: Long) {
        val c = auth(ip, port, me, peer)
        try {
            Frames.write(c.out, JSONObject().put("op", "msg").put("mid", mid).put("text", text).put("ts", ts))
            val r = Frames.read(c.inp)
            if (!r.optBoolean("ok")) throw ProtoException(r.optString("err", "Message rejected"))
        } finally {
            c.close()
        }
    }

    fun sendFile(
        ctx: Context, ip: String, port: Int, me: Me, peer: Peer,
        mid: String, uri: Uri, name: String, ts: Long,
        progress: (Long, Long) -> Unit
    ) {
        val resolver = ctx.contentResolver
        val buf = ByteArray(CHUNK)

        // Pass 1: size + sha256 (receiver verifies the checksum at the end)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        resolver.openInputStream(uri)?.use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                total += n
            }
        } ?: throw ProtoException("File open nahi hui")
        val sha = digest.digest().toHex()

        val c = auth(ip, port, me, peer)
        try {
            Frames.write(
                c.out,
                JSONObject()
                    .put("op", "file")
                    .put("mid", mid)
                    .put("name", name)
                    .put("size", total)
                    .put("sha256", sha)
                    .put("ts", ts)
            )
            val r = Frames.read(c.inp)
            if (!r.optBoolean("ok")) {
                if (r.optString("err") == "declined") throw DeclinedException()
                throw ProtoException(r.optString("err", "File rejected"))
            }

            // Pass 2: stream the bytes
            var sent = 0L
            resolver.openInputStream(uri)?.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    c.out.write(buf, 0, n)
                    sent += n
                    progress(sent, total)
                }
            } ?: throw ProtoException("File open nahi hui")
            c.out.flush()

            val fin = Frames.read(c.inp)
            if (!fin.optBoolean("ok")) throw ProtoException(fin.optString("err", "Receiver error"))
        } finally {
            c.close()
        }
    }
}
