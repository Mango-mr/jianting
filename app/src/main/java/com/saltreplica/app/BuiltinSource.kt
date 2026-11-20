package com.saltreplica.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 内置在线搜索：不用添加任何音源，打开「音乐源」页就能搜歌、试听、下载。
 *
 * 两个来源：
 * · 酷我  —— 能直接换出 mp3 直链，作为主力（Kuwo 的播放地址在播放前才去取）
 * · 网易云 —— 作为补充，它的官方跳转对会员/无版权曲目会返回 404 页面
 *
 * 版权受限的曲目拿不到音频时，播放会失败，换一个版本或关键词即可。
 */
object BuiltinSource {

    const val NAME = "内置在线搜索"
    const val DISPLAY_NAME = "内置在线搜索"
    private const val SUBTITLE = "酷我、网易云、酷狗可单独开关；点行登录账号，会员曲目才能出音质"

    /** 内置源在列表里的占位 id（负数，表示不可删除、不可关闭） */
    const val BUILTIN_ID = -1L

    /** 酷我的曲目先存成 kuwo://MUSIC_xxx，播放/下载时再换成真实直链 */
    private const val KUWO_PREFIX = "kuwo://"

    /** 酷狗曲目存成 kugou://<hash>|<albumId>，播放前换成真实地址 */
    private const val KUGOU_PREFIX = "kugou://"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    // ---------------- 列表占位 ----------------

    fun builtinRow(): MusicSource = MusicSource(
        id = BUILTIN_ID,
        name = DISPLAY_NAME,
        url = SUBTITLE,
        enabled = true
    )

    fun isKuwoUrl(url: String): Boolean = url.startsWith(KUWO_PREFIX)

    fun isKugouUrl(url: String): Boolean = url.startsWith(KUGOU_PREFIX)

    // ---------------- 搜索 ----------------

    suspend fun search(keyword: String): Result<List<OnlineTrack>> = withContext(Dispatchers.IO) {
        val useKuwo = Accounts.providerEnabled(Accounts.KUWO)
        val useNetease = Accounts.providerEnabled(Accounts.NETEASE)
        val useKugou = Accounts.providerEnabled(Accounts.KUGOU)
        if (!useKuwo && !useNetease && !useKugou) {
            return@withContext Result.failure(IllegalStateException("内置的酷我、网易云、酷狗都被关掉了"))
        }
        val errors = ArrayList<Throwable>(3)
        val kuwo = if (useKuwo) runCatching { searchKuwo(keyword) }
            .onFailure { errors.add(it) }
            .getOrDefault(emptyList()) else emptyList()
        val netease = if (useNetease) runCatching { searchNetease(keyword) }
            .onFailure { errors.add(it) }
            .getOrDefault(emptyList()) else emptyList()
        val kugou = if (useKugou) runCatching { searchKugou(keyword) }
            .onFailure { errors.add(it) }
            .getOrDefault(emptyList()) else emptyList()
        when {
            kuwo.isNotEmpty() || netease.isNotEmpty() || kugou.isNotEmpty() ->
                Result.success(kuwo + netease + kugou)
            errors.isNotEmpty() -> Result.failure(errors.first())
            else -> Result.success(emptyList())
        }
    }

    /** 把 kuwo://MUSIC_xxx 换成真正的 mp3 地址；拿不到返回 null */
    suspend fun resolveKuwo(url: String): String? = withContext(Dispatchers.IO) {
        val rid = url.removePrefix(KUWO_PREFIX).trim()
        if (rid.isBlank()) return@withContext null
        val numeric = rid.removePrefix("MUSIC_").trim()

        // 登录后走网页接口：带 Cookie + csrf，才能拿到账号可听的音质
        if (Accounts.loggedIn(Accounts.KUWO) && numeric.isNotBlank()) {
            val high = runCatching { fetchKuwoWebUrl(numeric) }.getOrNull()
            if (!high.isNullOrBlank()) return@withContext high
        }

        var resolved: String? = null
        // 酷我的换链服务偶尔会握手超时，重试一次基本都能拿到
        for (attempt in 0 until 2) {
            resolved = runCatching { fetchKuwoPlayUrl(rid) }
                .getOrNull()
                ?.takeIf { it.startsWith("http") }
            if (resolved != null) break
            if (attempt == 0) Thread.sleep(300)
        }
        resolved
    }

    /**
     * 网易云换链。走歌单同款接口，登录后 level=lossless 能拿到无损，未登录最多给到标准音质。
     * 接口给不出地址时再退回外链播放地址。
     */
    suspend fun resolveNetease(songId: String): String? = withContext(Dispatchers.IO) {
        if (songId.isBlank()) return@withContext null
        val levels = if (Accounts.loggedIn(Accounts.NETEASE)) {
            listOf("lossless", "exhigh", "standard")
        } else {
            listOf("exhigh", "standard")
        }
        for (level in levels) {
            val url = runCatching { fetchNeteaseUrl(songId, level) }.getOrNull()
            if (!url.isNullOrBlank()) return@withContext url
        }
        songId.toLongOrNull()?.let { playUrl(it) }
    }

    // ---------------- 酷我 ----------------

    /**
     * 酷我的搜索接口返回的是单引号「伪 JSON」，标准解析器会直接失败，
     * 这里按记录切块再用正则抠字段，不依赖 JSON 语法。
     */
    fun parseKuwo(body: String, limit: Int = 25): List<OnlineTrack> {
        if (body.isBlank()) return emptyList()
        val start = body.indexOf("'abslist':[")
        if (start < 0) return emptyList()
        var list = body.substring(start + "'abslist':[".length)
        // 不能用 "],'" 截断：记录里有 'SUBLIST':[] 这种空数组，会把列表提前切断

        val records = list.split("},{")
        val out = ArrayList<OnlineTrack>()
        for (raw in records) {
            if (out.size >= limit) break
            if (!raw.contains("MUSICRID")) continue
            val fields = kuwoFields(raw)
            val rid = fields["MUSICRID"].orEmpty().trim()
            if (!rid.startsWith("MUSIC_")) continue
            val name = cleanKuwoText(fields["NAME"])
            if (name.isBlank()) continue
            val artist = cleanKuwoText(fields["ARTIST"]).ifBlank { "未知歌手" }
            val album = cleanKuwoText(fields["ALBUM"])
            val seconds = fields["DURATION"]?.toLongOrNull() ?: 0L
            val pic = fields["PICPATH"].orEmpty().trim()
            val cover = when {
                pic.startsWith("http") -> pic
                pic.isNotEmpty() -> "https://img1.kuwo.cn" + pic
                else -> ""
            }
            out.add(
                OnlineTrack(
                    title = name,
                    artist = artist,
                    album = album,
                    url = KUWO_PREFIX + rid,
                    duration = seconds * 1000,
                    sourceName = "酷我",
                    platform = "kw",
                    songId = rid,
                    coverUrl = cover
                )
            )
        }
        return out
    }

    private fun searchKuwo(keyword: String): List<OnlineTrack> {
        val query = URLEncoder.encode(keyword, "UTF-8")
        val target = "http://search.kuwo.cn/r.s?all=$query&ft=music&itemset=web_2013" +
                "&client=kt&pn=0&rn=25&rformat=json&encoding=utf8"
        val body = httpGet(target, referer = "http://www.kuwo.cn/")
        if (body.isBlank()) throw IllegalStateException("酷我没有返回内容")
        return parseKuwo(body)
    }

    private fun fetchKuwoPlayUrl(rid: String): String? {
        val target = "http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3" +
                "&response=url&rid=" + URLEncoder.encode(rid, "UTF-8")
        val body = httpGet(target, referer = "http://www.kuwo.cn/").trim()
        return body.takeIf { it.startsWith("http") }?.substringBefore('\n')?.trim()
    }

    /** 网页版换链接口：必须带 Cookie 与 csrf，否则会返回「The request is illegal」 */
    private fun fetchKuwoWebUrl(numericId: String): String? {
        val token = Accounts.kuwoToken()
        if (token.isBlank()) return null
        val target = "https://www.kuwo.cn/api/v1/www/music/playUrl?mid=$numericId&type=music&httpsStatus=1"
        val body = httpGet(
            target,
            referer = "https://www.kuwo.cn/",
            extraHeaders = mapOf("csrf" to token)
        )
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val data = json.optJSONObject("data") ?: return null
        return data.optString("url").ifBlank { data.optString("playUrl") }.takeIf { it.startsWith("http") }
    }

    private val KUWO_FIELD = Regex("'([A-Za-z0-9_]+)':('(?:[^']*)'|[^,}]*)")

    private fun kuwoFields(record: String): Map<String, String> {
        val map = HashMap<String, String>(24)
        KUWO_FIELD.findAll(record).forEach { match ->
            val key = match.groupValues[1]
            var value = match.groupValues[2]
            if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length - 1)
            }
            map[key] = value
        }
        return map
    }

    private fun cleanKuwoText(raw: String?): String =
        raw.orEmpty()
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim()

    // ---------------- 网易云 ----------------

    fun playUrl(songId: Long): String =
        "https://music.163.com/song/media/outer/url?id=$songId.mp3"

    /** 解析网易云搜索结果；单独抽出来方便单测 */
    fun parse(body: String, limit: Int = 30): List<OnlineTrack> {
        if (body.isBlank()) return emptyList()
        val songs = runCatching {
            JSONObject(body).optJSONObject("result")?.optJSONArray("songs")
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<OnlineTrack>()
        for (i in 0 until songs.length()) {
            if (out.size >= limit) break
            val song = songs.optJSONObject(i) ?: continue
            val id = song.optLong("id", 0L)
            val name = song.optString("name").trim()
            if (id <= 0L || name.isBlank()) continue

            val artists = song.optJSONArray("artists")
            val artist = buildString {
                if (artists != null) {
                    for (j in 0 until artists.length()) {
                        val one = artists.optJSONObject(j)?.optString("name").orEmpty().trim()
                        if (one.isBlank()) continue
                        if (isNotEmpty()) append('/')
                        append(one)
                    }
                }
            }
            val album = song.optJSONObject("album")?.optString("name").orEmpty().trim()

            out.add(
                OnlineTrack(
                    title = name,
                    artist = artist.ifBlank { "未知歌手" },
                    album = album,
                    url = playUrl(id),
                    duration = song.optLong("duration", 0L),
                    sourceName = "网易云",
                    platform = "wy",
                    songId = id.toString()
                )
            )
        }
        return out
    }

    private fun searchNetease(keyword: String): List<OnlineTrack> {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val target = "https://music.163.com/api/search/get?s=$encoded&type=1&limit=30&offset=0"
        val body = httpGet(target, referer = "https://music.163.com/")
        if (body.isBlank()) throw IllegalStateException("网易云没有返回内容")
        return withNeteaseCovers(parse(body))
    }

    /**
     * 搜索结果里的 album 只有 picId，没有 picUrl；
     * 再查一次 song/detail 批量补封面，一次请求覆盖整页结果。
     */
    private fun withNeteaseCovers(tracks: List<OnlineTrack>): List<OnlineTrack> {
        if (tracks.isEmpty()) return tracks
        val ids = tracks.mapNotNull { it.songId.toLongOrNull() }
        if (ids.isEmpty()) return tracks
        val target = "https://music.163.com/api/song/detail?ids=[" + ids.joinToString(",") + "]"
        val body = runCatching { httpGet(target, referer = "https://music.163.com/") }.getOrDefault("")
        if (body.isBlank()) return tracks
        val map = HashMap<String, String>()
        runCatching {
            val songs = JSONObject(body).optJSONArray("songs") ?: return@runCatching
            for (i in 0 until songs.length()) {
                val song = songs.optJSONObject(i) ?: continue
                val id = song.optLong("id", 0L).toString()
                val pic = song.optJSONObject("album")?.optString("picUrl").orEmpty()
                if (id.isNotBlank() && pic.isNotBlank()) map[id] = pic
            }
        }
        return tracks.map { it.copy(coverUrl = map[it.songId].orEmpty()) }
    }

    /** 网易云换链接口：level = lossless / exhigh / standard */
    private fun fetchNeteaseUrl(songId: String, level: String): String? {
        val target = "https://music.163.com/api/song/enhance/player/url/v1" +
                "?ids=[$songId]&level=$level&encodeType=flac"
        val body = httpGet(target, referer = "https://music.163.com/")
        val data = runCatching {
            JSONObject(body).optJSONArray("data")?.optJSONObject(0)
        }.getOrNull() ?: return null
        return data.optString("url").takeIf { it.startsWith("http") }
    }

    // ---------------- 酷狗 ----------------

    /** 解析酷狗搜索 JSON；单独抽出来方便单测 */
    fun parseKugou(body: String, limit: Int = 20): List<OnlineTrack> {
        if (body.isBlank()) return emptyList()
        val info = runCatching {
            JSONObject(body).optJSONObject("data")?.optJSONArray("info")
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<OnlineTrack>()
        for (i in 0 until info.length()) {
            if (out.size >= limit) break
            val item = info.optJSONObject(i) ?: continue
            val hash = item.optString("hash").trim()
            val name = item.optString("songname").trim()
            if (hash.isBlank() || name.isBlank()) continue
            val albumId = item.optLong("album_id", 0L)
            val seconds = item.optLong("duration", 0L)
            out.add(
                OnlineTrack(
                    title = name,
                    artist = item.optString("singername").trim().ifBlank { "未知歌手" },
                    album = item.optString("album_name").trim(),
                    url = KUGOU_PREFIX + hash + "|" + albumId,
                    duration = seconds * 1000,
                    sourceName = "酷狗",
                    platform = "kg",
                    songId = hash,
                    coverUrl = if (albumId > 0L) "https://imge.kugou.com/stdmusic/240/$albumId.jpg" else ""
                )
            )
        }
        return out
    }

    private fun searchKugou(keyword: String): List<OnlineTrack> {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val target = "http://mobilecdn.kugou.com/api/v3/search/song?format=json" +
                "&keyword=$encoded&page=1&pagesize=20&showtype=1"
        val body = httpGet(target, referer = "https://www.kugou.com/")
        if (body.isBlank()) throw IllegalStateException("酷狗没有返回内容")
        return parseKugou(body)
    }

    /** 酷狗换链：先试老的 playInfo 接口，再试网页 play/getdata；付费曲目两边都可能为空 */
    suspend fun resolveKugou(url: String): String? = withContext(Dispatchers.IO) {
        val raw = url.removePrefix(KUGOU_PREFIX)
        val hash = raw.substringBefore('|').trim()
        val albumId = raw.substringAfter('|', "").trim()
        if (hash.isBlank()) return@withContext null

        runCatching {
            val body = httpGet(
                "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$hash",
                referer = "https://m.kugou.com/"
            )
            JSONObject(body).optString("url").takeIf { it.startsWith("http") }
        }.getOrNull()?.let { return@withContext it }

        runCatching {
            val target = "https://www.kugou.com/yy/index.php?r=play/getdata&hash=$hash" +
                    (if (albumId.isNotBlank()) "&album_id=$albumId" else "")
            val body = httpGet(target, referer = "https://www.kugou.com/")
            val data = JSONObject(body).optJSONObject("data") ?: return@runCatching null
            data.optString("play_url").ifBlank { data.optString("url") }
                .takeIf { it.startsWith("http") }
        }.getOrNull()
    }

    // ---------------- 公共 ----------------

    private fun httpGet(
        target: String,
        referer: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            val cookie = Accounts.cookieForHost(URL(target).host.orEmpty())
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            extraHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream
            else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}
