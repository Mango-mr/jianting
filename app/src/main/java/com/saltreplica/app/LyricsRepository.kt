package com.saltreplica.app

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LrcLine(val timeMs: Long, val text: String)

object LyricsParser {
    // 分钟、秒、毫秒三段都要捕获，之前漏了括号导致 groupValues 越界、所有 LRC 都解析失败
    private val TAG = Regex("\\[([0-9]{1,2}):([0-9]{1,2})(?:[.:]([0-9]{1,3}))?\\]")

    fun parse(lrc: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        for (raw in lrc.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val matches = TAG.findAll(line)
            if (!matches.iterator().hasNext()) continue
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = m.groupValues[3]
                val frac = when {
                    fracRaw.isEmpty() -> 0L
                    fracRaw.length == 3 -> fracRaw.toLongOrNull() ?: 0L
                    fracRaw.length == 2 -> (fracRaw.toLongOrNull() ?: 0L) * 10
                    else -> (fracRaw.toLongOrNull() ?: 0L) * 100
                }
                out.add(LrcLine(min * 60_000 + sec * 1000 + frac, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** 内嵌歌词常常是没有时间轴的纯文本：按行数把整首歌均分，至少保证能跟着滚动 */
    fun unsynced(text: String, durationMs: Long): List<LrcLine> {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val total = if (durationMs in 20_000..6 * 60 * 60_000L) durationMs else lines.size * 3_500L
        val step = (total / lines.size).coerceAtLeast(1_200L)
        return lines.mapIndexed { index, s -> LrcLine(index * step, s) }
    }
}

object LyricsRepository {

    private val cache = LruCache<String, List<LrcLine>>(40)

    suspend fun load(track: Track): List<LrcLine> = withContext(Dispatchers.IO) {
        val key = "local:${track.id}"
        cache.get(key)?.let { return@withContext it }

        // 1) 内嵌歌词 / 同名 .lrc —— 本地优先，准确且不依赖网络
        val local = readLocal(track)
        if (local.isNotEmpty()) {
            cache.put(key, local)
            return@withContext local
        }

        // 2) 在线兜底
        load(
            track.id.toString(),
            track.titleSafe,
            track.artistSafe,
            track.albumSafe,
            track.durationMs
        )
    }

    private fun readLocal(track: Track): List<LrcLine> {
        val path = track.path
        if (path.isBlank()) return emptyList()
        val file = File(path)
        if (!file.exists() || !file.canRead()) return emptyList()

        val raw = runCatching { TagReader.readSidecarLrc(file) }.getOrNull()
            ?: runCatching { TagReader.read(file).lyrics }.getOrNull()
        if (raw.isNullOrBlank()) return emptyList()

        val parsed = LyricsParser.parse(raw)
        if (parsed.isNotEmpty()) return parsed
        return LyricsParser.unsynced(raw, track.durationMs)
    }

    /** 通用入口：本地歌曲与在线歌曲都能用标题/歌手找词 */
    suspend fun load(
        key: String,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L
    ): List<LrcLine> = withContext(Dispatchers.IO) {
        cache.get(key)?.let { return@withContext it }
        val raw = fetchOnline(title, artist, album, durationMs)
        val parsed = if (raw != null) LyricsParser.parse(raw) else emptyList()
        if (parsed.isNotEmpty()) cache.put(key, parsed)
        parsed
    }

    private fun fetchOnline(title: String, artist: String, album: String, durationMs: Long): String? {
        if (title.isBlank()) return null
        val knownArtist = artist.isNotBlank() && artist != "未知歌手"
        val knownAlbum = album.isNotBlank() && album != "未知专辑"
        // lrclib 精确
        try {
            val params = mutableListOf<String>()
            if (knownArtist) {
                params.add("artist_name=" + URLEncoder.encode(artist, "UTF-8"))
            }
            params.add("track_name=" + URLEncoder.encode(title, "UTF-8"))
            if (knownAlbum) {
                params.add("album_name=" + URLEncoder.encode(album, "UTF-8"))
            }
            if (durationMs > 0) params.add("duration=" + (durationMs / 1000))
            val json = getObject("https://lrclib.net/api/get?" + params.joinToString("&"), false)
            json?.optString("syncedLyrics", "")?.takeIf { LyricsParser.parse(it).isNotEmpty() }?.let { return it }
        } catch (_: Exception) {
        }
        // lrclib 搜索
        try {
            val q = listOf(artist, title)
                .filter { it.isNotBlank() && it != "未知歌手" }
                .joinToString(" ")
            if (q.isNotBlank()) {
                val arr = getArray("https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8"))
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        if (item.optBoolean("instrumental", false)) continue
                        val synced = item.optString("syncedLyrics", "")
                        if (synced.isNotEmpty() && LyricsParser.parse(synced).isNotEmpty()) return synced
                    }
                }
            }
        } catch (_: Exception) {
        }
        // 网易云
        try {
            fetchNetEase(title, artist)?.let { return it }
        } catch (_: Exception) {
        }
        return null
    }

    private fun fetchNetEase(title: String, artist: String): String? {
        val normTitle = normalize(title)
        if (normTitle.isEmpty()) return null
        val query = buildString {
            append(title)
            if (artist.isNotBlank() && artist != "未知歌手") append(' ').append(artist)
        }
        val searchJson = getObject(
            "https://music.163.com/api/search/get/web?s=" + URLEncoder.encode(query, "UTF-8") + "&type=1&limit=10",
            true
        ) ?: return null
        val songs = searchJson.optJSONObject("result")?.optJSONArray("songs") ?: return null
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val id = song.optLong("id", 0)
            if (id <= 0) continue
            val name = normalize(song.optString("name", ""))
            if (name.isNotEmpty() && !name.contains(normTitle) && !normTitle.contains(name)) continue
            val lyricJson = getObject(
                "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1", true
            ) ?: continue
            val lyric = lyricJson.optJSONObject("lrc")?.optString("lyric", "") ?: continue
            if (lyric.isNotEmpty() && LyricsParser.parse(lyric).isNotEmpty()) return lyric
        }
        return null
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace(Regex("（[^）]*）"), "")
        .replace(Regex("\\([^)]*\\)"), "")
        .replace(Regex("\\[.*?]"), "")
        .replace(Regex("[《》\"'\\s]"), "")

    private fun getObject(urlStr: String, netease: Boolean): JSONObject? {
        val body = getBody(urlStr, netease) ?: return null
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun getArray(urlStr: String): JSONArray? {
        val body = getBody(urlStr, false) ?: return null
        return try {
            JSONArray(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun getBody(urlStr: String, netease: Boolean): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty(
                "User-Agent",
                if (netease) "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 SimpleListen"
                else "SimpleListen/1.0 (local music player)"
            )
            if (netease) conn.setRequestProperty("Referer", "https://music.163.com")
            if (conn.responseCode != 200) null
            else BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
