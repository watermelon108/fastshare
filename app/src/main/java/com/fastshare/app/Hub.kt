package com.fastshare.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** An incoming file waiting for the user's Accept / Decline. */
class FileRequest(val fromAlias: String, val name: String, val size: Long) {
    val latch = CountDownLatch(1)
    @Volatile var accepted = false
}

/** A row in the device list: paired (saved) and/or currently online (discovered). */
data class Device(
    val id: String,
    val alias: String,
    val paired: Boolean,
    val online: Boolean,
    val ip: String?,
    val port: Int,
    val peer: Peer?
)

/**
 * App-wide state and actions. The UI reads the StateFlows; the service
 * (server + discovery) runs from here.
 */
object Hub : Events {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var app: Context
    lateinit var db: Db
    private lateinit var prefs: SharedPreferences
    private lateinit var myId: String
    private var myFp = ""
    private var server: Server? = null
    private var discovery: Discovery? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var started = false

    val devices = MutableStateFlow<List<Device>>(emptyList())
    val selectedId = MutableStateFlow<String?>(null)
    val chatTick = MutableStateFlow(0)
    val progress = MutableStateFlow(-1f)           // -1 = hidden
    val unread = MutableStateFlow<Set<String>>(emptySet())
    val fileRequest = MutableStateFlow<FileRequest?>(null)
    val pairingCode = MutableStateFlow<String?>(null)
    val alias = MutableStateFlow("")
    val autoAccept = MutableStateFlow(false)
    val notifications = MutableStateFlow(true)
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 32)

    fun me(): Me = Me(myId, alias.value)

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        Notifier.ensureChannels(app)
        prefs = app.getSharedPreferences("fastshare", Context.MODE_PRIVATE)
        db = Db(app)
        myId = prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("id", it).apply()
        }
        alias.value = prefs.getString("alias", null) ?: Build.MODEL
        autoAccept.value = prefs.getBoolean("auto_accept", false)
        notifications.value = prefs.getBoolean("notifications", true)

        val identity = Crypto.loadOrCreate(app.filesDir)
        myFp = identity.fingerprint

        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("fastshare").apply {
            setReferenceCounted(false)
            acquire()
        }

        val srv = Server(app, identity, db, ::me, myFp, this)
        server = srv
        try {
            srv.start()
        } catch (e: Exception) {
            toast("Port 53317 pe pehle se kuch chal raha hai")
        }

        val disc = Discovery(::me) { refreshDevices() }
        discovery = disc
        disc.start()

        scope.launch {
            while (isActive) {
                refreshDevices()
                delay(2000)
            }
        }
    }

    // ---------------------------------------------------------------- devices

    fun refreshDevices() {
        val online = discovery?.snapshot()?.associateBy { it.id } ?: emptyMap()
        val paired = db.listPeers().associateBy { it.id }
        val ids: Set<String> = paired.keys + online.keys
        val list = ids.map { id ->
            val p = paired[id]
            val o = online[id]
            Device(
                id = id,
                alias = o?.alias ?: p?.alias ?: "Device",
                paired = p != null,
                online = o != null,
                ip = o?.ip,
                port = o?.port ?: PORT,
                peer = p
            )
        }.sortedWith(compareBy<Device>({ !it.online }, { !it.paired }, { it.alias.lowercase() }))
        devices.value = list
        if (selectedId.value != null && selectedId.value !in ids) selectedId.value = null
    }

    fun select(id: String) {
        selectedId.value = id
        unread.value = unread.value - id
    }

    fun removeDevice(id: String) {
        db.deletePeer(id)
        selectedId.value = null
        refreshDevices()
    }

    // ---------------------------------------------------------------- pairing

    fun newPairingCode(): String {
        val code = server!!.newCode()
        pairingCode.value = code
        scope.launch {
            delay(120_000L)
            if (pairingCode.value == code) pairingCode.value = null
        }
        return code
    }

    fun pair(dev: Device, code: String) {
        val ip = dev.ip ?: return
        scope.launch {
            try {
                val peer = Client.pair(ip, dev.port, code, me(), myFp)
                if (peer.id != dev.id) throw ProtoException("Device id match nahi hua")
                db.savePeer(peer.copy(alias = dev.alias))
                pairingCode.value = null
                toast("Paired: ${dev.alias}")
            } catch (e: Exception) {
                toast("Pair fail: ${e.message}")
            }
            refreshDevices()
        }
    }

    // ---------------------------------------------------------------- sending

    fun sendText(dev: Device, text: String) {
        val peer = dev.peer ?: return
        val ip = dev.ip ?: return
        val mid = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis() / 1000
        db.addMessage(Msg(mid, peer.id, "out", "text", text, null, ts, "pending"))
        chatTick.value++
        scope.launch {
            try {
                Client.sendText(ip, dev.port, me(), peer, mid, text, ts)
                db.setStatus(mid, "ok")
            } catch (e: Exception) {
                db.setStatus(mid, "failed")
                toast("Message fail: ${e.message}")
            }
            chatTick.value++
        }
    }

    fun sendFiles(dev: Device, uris: List<Uri>) {
        val peer = dev.peer ?: return
        val ip = dev.ip ?: return
        scope.launch {
            for (uri in uris) {
                val name = queryName(uri)
                val mid = UUID.randomUUID().toString()
                val ts = System.currentTimeMillis() / 1000
                db.addMessage(Msg(mid, peer.id, "out", "file", name, uri.toString(), ts, "pending"))
                chatTick.value++
                try {
                    Client.sendFile(app, ip, dev.port, me(), peer, mid, uri, name, ts) { done, total ->
                        progress.value = if (total > 0) done.toFloat() / total else 1f
                    }
                    db.setStatus(mid, "ok")
                    toast("Bhej diya: $name")
                } catch (e: DeclinedException) {
                    db.setStatus(mid, "failed")
                    toast(e.message ?: "Declined")
                } catch (e: Exception) {
                    db.setStatus(mid, "failed")
                    toast("$name fail: ${e.message}")
                }
                progress.value = -1f
                chatTick.value++
            }
        }
    }

    private fun queryName(uri: Uri): String {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "file"
        }
        return uri.lastPathSegment ?: "file"
    }

    // ---------------------------------------------------------------- incoming (Events)

    override fun onMessage(peer: Peer, text: String, ts: Long) = incoming(peer, text)

    override fun onFile(peer: Peer, name: String) {
        progress.value = -1f
        incoming(peer, "📎 $name")
    }

    private fun incoming(peer: Peer, text: String) {
        chatTick.value++
        if (selectedId.value != peer.id) unread.value = unread.value + peer.id
        refreshDevices()
        if (notifications.value) Notifier.show(app, peer.alias, text)
    }

    override fun onPaired(peer: Peer) {
        db.savePeer(peer)
        pairingCode.value = null
        toast("Paired: ${peer.alias}")
        refreshDevices()
    }

    override fun onProgress(done: Long, total: Long) {
        progress.value = if (total > 0) done.toFloat() / total else 1f
    }

    override fun onError(msg: String) = toast(msg)

    override fun askFile(peer: Peer, name: String, size: Long): Boolean {
        if (autoAccept.value) return true
        val req = FileRequest(peer.alias, name, size)
        fileRequest.value = req
        if (notifications.value) Notifier.show(app, peer.alias, "File bhej raha hai: $name")
        val ok = req.latch.await(60, TimeUnit.SECONDS) && req.accepted
        if (fileRequest.value === req) fileRequest.value = null
        return ok
    }

    fun answerFileRequest(accept: Boolean) {
        val r = fileRequest.value ?: return
        r.accepted = accept
        r.latch.countDown()
        fileRequest.value = null
    }

    // ---------------------------------------------------------------- settings

    fun saveSettings(newAlias: String, auto: Boolean, notif: Boolean) {
        val a = newAlias.trim().take(40).ifEmpty { alias.value }
        alias.value = a
        autoAccept.value = auto
        notifications.value = notif
        prefs.edit()
            .putString("alias", a)
            .putBoolean("auto_accept", auto)
            .putBoolean("notifications", notif)
            .apply()
    }

    private fun toast(text: String) {
        toasts.tryEmit(text)
    }
}
