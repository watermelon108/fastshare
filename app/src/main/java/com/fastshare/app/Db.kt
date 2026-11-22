package com.fastshare.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Peer(val id: String, val alias: String, val fp: String, val key: String)

data class Msg(
    val mid: String,
    val peerId: String,
    val direction: String,   // "in" or "out"
    val kind: String,        // "text" or "file"
    val body: String,        // text, or file name
    val fileUri: String?,
    val ts: Long,            // unix seconds
    val status: String       // ok / pending / failed
)

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "fastshare.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE peers (id TEXT PRIMARY KEY, alias TEXT, fp TEXT, key TEXT NOT NULL, added INTEGER)")
        db.execSQL(
            "CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, mid TEXT UNIQUE, peer_id TEXT NOT NULL, " +
                "direction TEXT NOT NULL, kind TEXT NOT NULL, body TEXT, file_path TEXT, ts INTEGER, " +
                "status TEXT DEFAULT 'ok')"
        )
        db.execSQL("CREATE INDEX idx_msg_peer ON messages(peer_id, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun savePeer(p: Peer) {
        val cv = ContentValues().apply {
            put("id", p.id)
            put("alias", p.alias)
            put("fp", p.fp)
            put("key", p.key)
            put("added", System.currentTimeMillis() / 1000)
        }
        writableDatabase.insertWithOnConflict("peers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun getPeer(id: String): Peer? =
        readableDatabase.rawQuery("SELECT id, alias, fp, key FROM peers WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) {
                Peer(c.getString(0), c.getString(1) ?: "Device", c.getString(2) ?: "", c.getString(3))
            } else {
                null
            }
        }

    @Synchronized
    fun listPeers(): List<Peer> {
        val out = ArrayList<Peer>()
        readableDatabase.rawQuery("SELECT id, alias, fp, key FROM peers ORDER BY alias COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) {
                out.add(Peer(c.getString(0), c.getString(1) ?: "Device", c.getString(2) ?: "", c.getString(3)))
            }
        }
        return out
    }

    @Synchronized
    fun deletePeer(id: String) {
        writableDatabase.delete("messages", "peer_id = ?", arrayOf(id))
        writableDatabase.delete("peers", "id = ?", arrayOf(id))
    }

    @Synchronized
    fun addMessage(m: Msg) {
        val cv = ContentValues().apply {
            put("mid", m.mid)
            put("peer_id", m.peerId)
            put("direction", m.direction)
            put("kind", m.kind)
            put("body", m.body)
            put("file_path", m.fileUri)
            put("ts", m.ts)
            put("status", m.status)
        }
        writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    fun setStatus(mid: String, status: String) {
        val cv = ContentValues().apply { put("status", status) }
        writableDatabase.update("messages", cv, "mid = ?", arrayOf(mid))
    }

    @Synchronized
    fun listMessages(peerId: String, limit: Int = 300): List<Msg> {
        val out = ArrayList<Msg>()
        readableDatabase.rawQuery(
            "SELECT * FROM (SELECT mid, peer_id, direction, kind, body, file_path, ts, status FROM messages " +
                "WHERE peer_id = ? ORDER BY ts DESC, id DESC LIMIT ?) ORDER BY ts ASC",
            arrayOf(peerId, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Msg(
                        mid = c.getString(0),
                        peerId = c.getString(1),
                        direction = c.getString(2),
                        kind = c.getString(3),
                        body = c.getString(4) ?: "",
                        fileUri = if (c.isNull(5)) null else c.getString(5),
                        ts = c.getLong(6),
                        status = c.getString(7) ?: "ok"
                    )
                )
            }
        }
        return out
    }
}
