package com.saltreplica.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 扫描结果缓存：启动时先秒出上次的列表，再去后台重新扫描，
 * 避免每次都等 MediaStore / 文件遍历。
 */
object TrackStore {

    private const val FILE_NAME = "tracks_cache.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): List<Track> {
        val f = file(context)
        if (!f.exists() || f.length() <= 0) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val path = o.optString("path")
                val uriStr = o.optString("uri")
                if (path.isBlank()) continue
                out.add(
                    Track(
                        id = o.optLong("id"),
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        album = o.optString("album"),
                        durationMs = o.optLong("durationMs"),
                        path = path,
                        albumId = o.optLong("albumId"),
                        size = o.optLong("size"),
                        uri = if (uriStr.isBlank()) Uri.fromFile(File(path)) else Uri.parse(uriStr)
                    )
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun save(context: Context, tracks: List<Track>) {
        try {
            val arr = JSONArray()
            tracks.forEach { t ->
                arr.put(
                    JSONObject().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("artist", t.artist)
                        put("album", t.album)
                        put("durationMs", t.durationMs)
                        put("path", t.path)
                        put("albumId", t.albumId)
                        put("size", t.size)
                        put("uri", t.uri.toString())
                    }
                )
            }
            val f = file(context)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(arr.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }
}
