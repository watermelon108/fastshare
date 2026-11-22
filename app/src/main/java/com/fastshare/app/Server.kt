package com.fastshare.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** Callbacks from the server into the app (implemented by Hub). */
interface Events {
    fun onMessage(peer: Peer, text: String, ts: Long)
    fun onFile(peer: Peer, name: String)
    fun onPaired(peer: Peer)
    fun onProgress(done: Long, total: Long)
    fun onError(msg: String)
    fun askFile(peer: Peer, name: String, size: Long): Boolean
}

/** Receiving side: TLS listener, pairing, auth, incoming text and files. Mirrors server.py on Linux. */
class Server(
    private val ctx: Context,
    private val identity: Identity,
    private val db: Db,
    private val me: () -> Me,
    private val ownFp: String,
    private val events: Events
) {
    private val lock = Any()
    private var code: String? = null
    private var codeExp = 0L
    private var tries = 0
    @Volatile private var running = false
    private var listener: SSLServerSocket? = null

    /** Generates a fresh 6-digit code, valid for 2 minutes. */
    fun newCode(): String = synchronized(lock) {
        val c = "%06d".format(SecureRandom().nextInt(1_000_000))
        code = c
        codeExp = System.currentTimeMillis() + 120_000L
        tries = 0
        c
    }

    fun start() {
        val sock = Crypto.serverContext(identity).serverSocketFactory.createServerSocket(PORT) as SSLServerSocket
        sock.reuseAddress = true
        listener = sock
        running = true
        thread(isDaemon = true, name = "fs-accept") {
            while (running) {
                val s: SSLSocket = try {
                    sock.accept() as SSLSocket
                } catch (e: Exception) {
                    if (!running) break
                    continue
                }
                thread(isDaemon = true, name = "fs-conn") { serve(s) }
            }
        }
    }

    fun stop() {
        running = false
        try {
            listener?.close()
        } catch (_: Exception) {
        }
    }

    private fun serve(s: SSLSocket) {
        s.use { sock ->
            try {
                sock.soTimeout = 60_000
                sock.startHandshake()
                val inp = DataInputStream(BufferedInputStream(sock.inputStream))
                val out = DataOutputStream(BufferedOutputStream(sock.outputStream))
                session(inp, out)
            } catch (e: Exception) {
                events.onError("Connection error: ${e.message ?: "unknown"}")
            }
        }
    }

    private fun session(inp: DataInputStream, out: DataOutputStream) {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        val mine = me()
        Frames.write(out, JSONObject().put("op", "hello").put("id", mine.id).put("alias", mine.alias).put("nonce", nonce))
        val first = Frames.read(inp)
        when (first.optString("op")) {
            "pair" -> pair(first, out)
            "auth" -> authed(first, nonce, inp, out)
            else -> throw ProtoException("unexpected request")
        }
    }

    private fun pair(req: JSONObject, out: DataOutputStream) {
        val mine = me()
        val peerId = req.optString("id").take(64)
        val fpC = req.optString("fp_c")
        var key: ByteArray? = null
        synchronized(lock) {
            val c = code
            if (c != null && System.currentTimeMillis() <= codeExp && tries < MAX_PAIR_TRIES) {
                tries++
                if (Crypto.safeEq(req.optString("proof"), Crypto.pairProof(c, fpC, ownFp))) {
                    key = Crypto.pairKey(c, fpC, ownFp)
                    code = null
                }
            }
        }
        val k = key
        if (k == null || peerId.isEmpty() || peerId == mine.id) {
            Frames.write(out, JSONObject().put("ok", false).put("err", "Code galat ya expire ho gaya"))
            return
        }
        val peer = Peer(peerId, req.optString("alias", "Device").take(64), fpC, k.toHex())
        events.onPaired(peer)
        Frames.write(out, JSONObject().put("ok", true))
    }

    private fun authed(first: JSONObject, nonce: String, inp: DataInputStream, out: DataOutputStream) {
        val mine = me()
        val peer = db.getPeer(first.optString("id"))
        if (peer == null) {
            Frames.write(out, JSONObject().put("ok", false).put("err", "Not paired"))
            return
        }
        val key = peer.key.hexToBytes()
        if (!Crypto.safeEq(first.optString("mac"), Crypto.tag(key, nonce, "C", mine.id))) {
            Frames.write(out, JSONObject().put("ok", false).put("err", "Auth failed"))
            return
        }
        Frames.write(out, JSONObject().put("ok", true).put("mac", Crypto.tag(key, nonce, "S", peer.id)))

        val req = Frames.read(inp)
        when (req.optString("op")) {
            "msg" -> onMsg(peer, req, out)
            "file" -> onFile(peer, req, inp, out)
            else -> throw ProtoException("unknown operation")
        }
    }

    private fun onMsg(peer: Peer, req: JSONObject, out: DataOutputStream) {
        val text = req.optString("text").take(8000)
        val mid = req.optString("mid").take(64)
        if (text.isBlank() || mid.isEmpty()) {
            Frames.write(out, JSONObject().put("ok", false).put("err", "Empty message"))
            return
        }
        val ts = req.optLong("ts", System.currentTimeMillis() / 1000)
        db.addMessage(Msg(mid, peer.id, "in", "text", text, null, ts, "ok"))
        events.onMessage(peer, text, ts)
        Frames.write(out, JSONObject().put("ok", true))
    }

    private fun onFile(peer: Peer, req: JSONObject, inp: DataInputStream, out: DataOutputStream) {
        val name = safeName(req.optString("name"))
        val size = req.optLong("size", -1L)
        val expected = req.optString("sha256").lowercase()
        val mid = req.optString("mid").take(64)
        val ts = req.optLong("ts", System.currentTimeMillis() / 1000)
        if (size < 0 || expected.length != 64 || mid.isEmpty()) {
            Frames.write(out, JSONObject().put("ok", false).put("err", "Bad file header"))
            return
        }
        if (!events.askFile(peer, name, size)) {
            Frames.write(out, JSONObject().put("ok", false).put("err", "declined"))
            return
        }
        val uri = createDownload(name)
        if (uri == null) {
            Frames.write(out, JSONObject().put("ok", false).put("err", "Storage error"))
            return
        }
        Frames.write(out, JSONObject().put("ok", true))

        val resolver = ctx.contentResolver
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        try {
            resolver.openOutputStream(uri)!!.use { os ->
                val buf = ByteArray(CHUNK)
                while (received < size) {
                    val want = minOf(buf.size.toLong(), size - received).toInt()
                    val n = inp.read(buf, 0, want)
                    if (n < 0) throw ProtoException("connection dropped")
                    os.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    received += n
                    events.onProgress(received, size)
                }
            }
            if (!Crypto.safeEq(digest.digest().toHex(), expected)) {
                throw ProtoException("checksum mismatch, file discarded")
            }
            finishDownload(uri)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Frames.write(out, JSONObject().put("ok", false).put("err", e.message ?: "error"))
            throw e
        }
        db.addMessage(Msg(mid, peer.id, "in", "file", name, uri.toString(), ts, "ok"))
        events.onFile(peer, name)
        Frames.write(out, JSONObject().put("ok", true))
    }

    /** Creates a pending file in Downloads/FastShare (MediaStore, Android 10+). */
    private fun createDownload(name: String): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/FastShare")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
    }

    private fun finishDownload(uri: Uri) {
        val cv = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, cv, null, null)
    }

    companion object {
        const val MAX_PAIR_TRIES = 5
    }
}
