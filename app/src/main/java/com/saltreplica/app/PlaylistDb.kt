package com.saltreplica.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Playlist(val id: Long, val name: String, val count: Int)

/** 歌单数据库（SQLite，零外部依赖） */
class PlaylistDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "playlists.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE playlists (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE playlist_songs (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "playlist_id INTEGER NOT NULL," +
                    "track_id INTEGER NOT NULL," +
                    "position INTEGER NOT NULL DEFAULT 0," +
                    "UNIQUE(playlist_id, track_id))"
        )
        // 默认歌单「我喜欢」，永远排在第一个
        db.execSQL(
            "INSERT INTO playlists (name, created_at) VALUES ('我喜欢', 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun createPlaylist(name: String): Long {
        val v = ContentValues().apply {
            put("name", name)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("playlists", null, v)
    }

    fun renamePlaylist(id: Long, name: String) {
        writableDatabase.update(
            "playlists", ContentValues().apply { put("name", name) }, "_id=?",
            arrayOf(id.toString())
        )
    }

    fun deletePlaylist(id: Long) {
        writableDatabase.delete("playlists", "_id=?", arrayOf(id.toString()))
        writableDatabase.delete("playlist_songs", "playlist_id=?", arrayOf(id.toString()))
    }

    fun playlists(): List<Playlist> {
        val out = ArrayList<Playlist>()
        readableDatabase.rawQuery(
            "SELECT p._id, p.name, (SELECT COUNT(*) FROM playlist_songs s WHERE s.playlist_id = p._id) " +
                    "FROM playlists p ORDER BY p.created_at ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Playlist(c.getLong(0), c.getString(1) ?: "歌单", c.getInt(2)))
            }
        }
        return out
    }

    fun trackIds(playlistId: Long): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.rawQuery(
            "SELECT track_id FROM playlist_songs WHERE playlist_id=? ORDER BY position ASC",
            arrayOf(playlistId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    fun addTrack(playlistId: Long, trackId: Long): Boolean {
        val db = writableDatabase
        val position = nextPosition(playlistId)
        val v = ContentValues().apply {
            put("playlist_id", playlistId)
            put("track_id", trackId)
            put("position", position)
        }
        return try {
            db.insertWithOnConflict(
                "playlist_songs", null, v, SQLiteDatabase.CONFLICT_IGNORE
            ) != -1L
        } catch (_: Exception) {
            false
        }
    }

    fun removeTrack(playlistId: Long, trackId: Long) {
        writableDatabase.delete(
            "playlist_songs", "playlist_id=? AND track_id=?",
            arrayOf(playlistId.toString(), trackId.toString())
        )
    }

    fun moveTrack(playlistId: Long, from: Int, to: Int) {
        val ids = mutableListOf<Long>()
        readableDatabase.rawQuery(
            "SELECT track_id FROM playlist_songs WHERE playlist_id=? ORDER BY position ASC",
            arrayOf(playlistId.toString())
        ).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        if (from !in ids.indices || to !in ids.indices || from == to) return
        val item = ids.removeAt(from)
        ids.add(to, item)
        val db = writableDatabase
        for ((index, trackId) in ids.withIndex()) {
            val v = ContentValues().apply { put("position", index) }
            db.update(
                "playlist_songs", v,
                "playlist_id=? AND track_id=?",
                arrayOf(playlistId.toString(), trackId.toString())
            )
        }
    }

    private fun nextPosition(playlistId: Long): Int {
        var pos = 0
        readableDatabase.rawQuery(
            "SELECT IFNULL(MAX(position), -1) + 1 FROM playlist_songs WHERE playlist_id=?",
            arrayOf(playlistId.toString())
        ).use { c ->
            if (c.moveToFirst()) pos = c.getInt(0)
        }
        return pos
    }
}
