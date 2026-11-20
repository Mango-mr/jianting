package com.saltreplica.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 在线搜索结果 */
data class OnlineTrack(
    val title: String,
    val artist: String,
    val album: String,
    val url: String,
    val duration: Long,
    val sourceName: String,
    /** lx 脚本源用它换地址：kw / wy / tx / kg / mg */
    val platform: String = "",
    /** 对应平台的歌曲 id，酷我是 MUSIC_xxx，网易云是数字 id */
    val songId: String = "",
    /** 封面地址；接口没给就留空，列表用默认唱片图 */
    val coverUrl: String = ""
)

/**
 * 自定义音源客户端。
 *
 * 约定：一个音源就是一个返回 JSON 的 HTTP 接口。
 * 地址里可以写 {keyword} 占位符；没写时自动追加 ?keyword=。
 * 返回体支持以下任意一种结构：
 *   [ {...}, {...} ]                          // 顶层数组
 *   { "data": [ ... ] }
 *   { "data": { "list": [ ... ] } }
 *   { "result": { "songs": [ ... ] } }
 * 单曲字段兼容 name/title/songName、artist/singer/author、album/albumName、url/songUrl/playUrl/mp3Url。
 */
object SourceClient {

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    suspend fun search(source: MusicSource, keyword: String): Result<List<OnlineTrack>> =
        withContext(Dispatchers.IO) {
            runCatching { request(source, keyword) }
        }

    /** 下载脚本 / 任意文本（跟随重定向） */
    suspend fun downloadText(url: String): String = withContext(Dispatchers.IO) {
        var target = url
        var connection: HttpURLConnection? = null
        repeat(5) {
            connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
            }
            val code = connection!!.responseCode
            if (code in 300..399) {
                val location = connection!!.getHeaderField("Location")
                connection!!.disconnect()
                if (location.isNullOrBlank()) throw IllegalStateException("重定向地址为空")
                target = if (location.startsWith("http")) location else URL(URL(target), location).toString()
                return@repeat
            }
            val stream = if (code in 200..299) connection!!.inputStream else connection!!.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            connection!!.disconnect()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return@withContext body
        }
        throw IllegalStateException("重定向次数过多")
    }

    private fun request(source: MusicSource, keyword: String): List<OnlineTrack> {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val target = if (source.url.contains("{keyword}")) {
            source.url.replace("{keyword}", encoded)
                .replace("{page}", "1")
        } else {
            source.url + (if (source.url.contains("?")) "&" else "?") + "keyword=" + encoded
        }
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        val body = try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream
            else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            connection.disconnect()
        }
        if (body.isBlank()) throw IllegalStateException("音源返回为空")
        return parse(body, source.name)
    }

    private fun parse(body: String, sourceName: String): List<OnlineTrack> {
        val trimmed = body.trim()
        val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        val array = findTrackArray(root) ?: return emptyList()
        val result = mutableListOf<OnlineTrack>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val title = item.pick("name", "title", "songName", "songname", "song")
            val url = item.pick("url", "songUrl", "playUrl", "mp3Url", "src", "link")
            if (title.isBlank() || url.isBlank()) continue
            if (!url.startsWith("http")) continue
            result.add(
                OnlineTrack(
                    title = title,
                    artist = item.pick("artist", "singer", "author", "artists", "artistName"),
                    album = item.pick("album", "albumName", "albumname"),
                    url = url,
                    duration = pickDuration(item),
                    sourceName = sourceName
                )
            )
        }
        return result
    }

    private fun findTrackArray(node: Any?, depth: Int = 0): JSONArray? {
        if (depth > 4 || node == null) return null
        when (node) {
            is JSONArray -> {
                if (node.length() == 0) return null
                if (node.optJSONObject(0) != null) return node
                return null
            }
            is JSONObject -> {
                for (key in listOf("data", "list", "songs", "result", "items", "records", "song_list")) {
                    if (!node.has(key)) continue
                    val child = node.opt(key)
                    val found = findTrackArray(child, depth + 1)
                    if (found != null) return found
                }
                // 兜底：遍历任意数组字段
                val keys = node.keys()
                while (keys.hasNext()) {
                    val child = node.opt(keys.next())
                    if (child is JSONArray) {
                        val found = findTrackArray(child, depth + 1)
                        if (found != null) return found
                    }
                }
            }
        }
        return null
    }

    private fun JSONObject.pick(vararg keys: String): String {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = opt(key)
            if (value is String && value.isNotBlank()) return value.trim()
            if (value is Number) return value.toString()
        }
        return ""
    }

    private fun pickDuration(item: JSONObject): Long {
        for (key in listOf("duration", "interval", "time", "length")) {
            if (!item.has(key)) continue
            val raw = item.opt(key)
            if (raw is Number) {
                val value = raw.toLong()
                return if (value > 10_000) value else value * 1000L
            }
            if (raw is String) {
                val value = raw.toLongOrNull() ?: continue
                return if (value > 10_000) value else value * 1000L
            }
        }
        return 0L
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val total = ms / 1000
        return String.format("%d:%02d", total / 60, total % 60)
    }
}
