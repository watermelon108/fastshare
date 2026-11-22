package com.fastshare.app

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

data class Found(val id: String, val alias: String, val ip: String, val port: Int, val seen: Long)

/** Same UDP multicast discovery as the Linux app (224.0.0.167:53317). */
class Discovery(private val me: () -> Me, private val onChange: () -> Unit) {

    private val group: InetAddress = InetAddress.getByName("224.0.0.167")
    private val found = ConcurrentHashMap<String, Found>()
    @Volatile private var running = false
    private var listenSock: MulticastSocket? = null
    private var sendSock: MulticastSocket? = null

    fun start() {
        running = true
        Thread({ listen() }, "fs-discovery-rx").apply { isDaemon = true }.start()
        Thread({ announce() }, "fs-discovery-tx").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        listenSock?.close()
        sendSock?.close()
    }

    fun snapshot(): List<Found> {
        val now = System.currentTimeMillis()
        found.entries.removeIf { now - it.value.seen > 10_000L }
        return found.values.toList()
    }

    private fun listen() {
        try {
            val s = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            s.joinGroup(group)
            s.soTimeout = 1000
            listenSock = s
            val buf = ByteArray(4096)
            while (running) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    s.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                try {
                    val j = JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
                    if (j.optString("app") != "fastshare") continue
                    val id = j.getString("id")
                    if (id == me().id) continue
                    val ip = pkt.address.hostAddress ?: continue
                    val alias = j.optString("alias", "Device").take(64)
                    val old = found[id]
                    found[id] = Found(id, alias, ip, j.optInt("port", PORT), System.currentTimeMillis())
                    if (old == null || old.alias != alias || old.ip != ip) onChange()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun announce() {
        try {
            val s = MulticastSocket().apply { timeToLive = 1 }
            sendSock = s
            while (running) {
                val mine = me()
                val payload = JSONObject()
                    .put("app", "fastshare")
                    .put("v", 1)
                    .put("id", mine.id)
                    .put("alias", mine.alias)
                    .put("port", PORT)
                    .put("type", "android")
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                try {
                    s.send(DatagramPacket(payload, payload.size, InetSocketAddress(group, PORT)))
                } catch (_: Exception) {
                }
                Thread.sleep(3000)
            }
        } catch (_: Exception) {
        }
    }
}
