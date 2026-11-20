package com.saltreplica.app

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * lx-music 脚本源运行环境。
 *
 * lx 的脚本（形如 flower/latest.js）只认 `globalThis.lx` 这套约定：
 * · lx.request(url, options, cb)  取网络
 * · lx.on('request', fn)          脚本注册「取播放地址」的回调
 * · lx.send('inited', {sources})   脚本上报自己支持的平台与音质
 *
 * 这里用系统 WebView 当 JS 引擎（脚本里大量可选链 / for-of，老牌 Rhino 跑不动），
 * 网络请求和回调通过 [Bridge] 同步/异步地接回 Kotlin。
 */
object LxEngine {

    private const val TAG = "LxEngine"
    private const val INIT_TIMEOUT_MS = 25_000L
    private const val CALL_TIMEOUT_MS = 20_000L

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    /** 一个已装好的脚本源 */
    data class LxInfo(
        val name: String = "",
        val version: String = "",
        /** 平台 → 该平台支持的音质（高→低或脚本上报顺序） */
        val platforms: Map<String, List<String>> = emptyMap(),
        val error: String? = null
    ) {
        /** 用于列表副标题 */
        fun summary(): String {
            if (error != null) return "脚本源 · 初始化失败"
            if (platforms.isEmpty()) return "脚本源"
            val best = platforms.entries.joinToString(" / ") { (platform, qualities) ->
                platformLabel(platform) + " " + qualities.joinToString(",")
            }
            return "脚本源 · $best"
        }

        fun qualitiesOf(platform: String): List<String> = platforms[platform].orEmpty()
    }

    private class Session(
        val id: Long,
        val webView: WebView,
        val bridge: Bridge,
        val ready: CompletableDeferred<Boolean>
    )

    private val sessions = HashMap<Long, Session>()
    private val infos = HashMap<Long, LxInfo>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, String>>>()
    private val seq = AtomicInteger(0)

    fun platformLabel(code: String): String = when (code) {
        "kw" -> "酷我"
        "wy" -> "网易云"
        "tx" -> "QQ音乐"
        "kg" -> "酷狗"
        "mg" -> "咪咕"
        else -> code
    }

    fun scriptFile(context: Context, id: Long): File {
        val dir = File(context.filesDir, "lx")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "source_$id.js")
    }

    /** 从脚本头部注释里读 @name / @version */
    fun readMeta(script: String): Pair<String, String> {
        val name = Regex("@name\\s+([^\\r\\n*]+)").find(script)?.groupValues?.get(1)?.trim().orEmpty()
        val version = Regex("@version\\s+([^\\r\\n*]+)").find(script)?.groupValues?.get(1)?.trim().orEmpty()
        return name to version
    }

    /**
     * 装一个脚本源：写入本地缓存并跑一次初始化，拿到平台能力。
     * 初始化失败也会返回带 error 的 info（列表里能看到原因）。
     */
    suspend fun install(context: Context, source: MusicSource, script: String): LxInfo {
        val meta = readMeta(script)
        runCatching { scriptFile(context, source.id).writeText(script, Charsets.UTF_8) }
        return prepare(context, source, meta.first, meta.second)
    }

    /** 已缓存过就直接返回内存里的信息 */
    suspend fun info(context: Context, source: MusicSource): LxInfo? {
        infos[source.id]?.let { return it }
        val file = scriptFile(context, source.id)
        if (!file.exists()) return null
        val script = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val meta = readMeta(script)
        return prepare(context, source, meta.first, meta.second)
    }

    /** 只查内存缓存，不触发初始化；给「点播放时快速判断」用 */
    fun cachedInfo(id: Long): LxInfo? = infos[id]

    fun supportsCached(id: Long, platform: String): Boolean =
        infos[id]?.let { it.error == null && it.qualitiesOf(platform).isNotEmpty() } == true

    /** 进「音乐源」页时把脚本源逐个跑起来，点播放时就不用等初始化 */
    suspend fun warmUp(context: Context, sources: List<MusicSource>) {
        sources.filter { it.isLx && it.enabled && infos[it.id] == null }.forEach { source ->
            runCatching { info(context, source) }
        }
    }

    /** 取播放地址；失败返回 null（调用方回退内置源） */
    suspend fun musicUrl(
        context: Context,
        source: MusicSource,
        platform: String,
        songId: String,
        title: String,
        artist: String
    ): String? {
        val info = info(context, source) ?: return null
        if (info.error != null) return null
        val qualities = info.qualitiesOf(platform)
        if (qualities.isEmpty()) return null
        val quality = pickQuality(qualities)
        val session = ensureSession(context, source) ?: return null

        val arg = JSONObject()
            .put("source", platform)
            .put("action", "musicUrl")
            .put(
                "info",
                JSONObject()
                    .put("type", quality)
                    .put(
                        "musicInfo",
                        JSONObject()
                            .put("songmid", songId)
                            .put("musicId", songId)
                            .put("id", songId)
                            .put("rid", songId)
                            .put("hash", songId)
                            .put("copyrightId", songId)
                            .put("name", title)
                            .put("singer", artist)
                            .put("albumName", "")
                    )
            )

        val payload = invoke(session, arg)?.trim()?.trim('"') ?: return null
        // 有的脚本返回字符串，有的返回 { url: ... } 对象
        val url = runCatching {
            if (payload.startsWith("{")) {
                val json = JSONObject(payload)
                json.optString("url").ifBlank { json.optString("data") }
            } else payload
        }.getOrNull() ?: payload
        return url.trim().takeIf { it.startsWith("http") }
    }

    /**
     * 让脚本搜索某个平台。
     * 返回脚本原始 payload（通常是 JSON 数组），由调用方解析。
     */
    suspend fun searchRaw(
        context: Context,
        source: MusicSource,
        platform: String,
        keyword: String
    ): String? {
        val info = info(context, source) ?: return null
        if (info.error != null) return null
        if (info.qualitiesOf(platform).isEmpty()) return null
        val session = ensureSession(context, source) ?: return null
        val arg = JSONObject()
            .put("source", platform)
            .put("action", "search")
            .put(
                "info",
                JSONObject()
                    .put("keyword", keyword)
                    .put("page", 1)
            )
        return invoke(session, arg)
    }

    /** 把一次 request 丢给 WebView，等脚本回包 */
    private suspend fun invoke(session: Session, arg: JSONObject): String? {
        val id = "c" + seq.incrementAndGet()
        val deferred = CompletableDeferred<Pair<Boolean, String>>()
        pending[id] = deferred
        val ok = withContext(Dispatchers.Main) {
            runCatching {
                session.webView.evaluateJavascript(
                    "window.__rid=${JSONObject.quote(id)};window.__callRequest($arg);",
                    null
                )
            }.isSuccess
        }
        if (!ok) {
            pending.remove(id)
            return null
        }
        val result = withTimeoutOrNull(CALL_TIMEOUT_MS) { deferred.await() }
        pending.remove(id)
        val (success, payload) = result ?: return null
        if (!success) return null
        return payload.trim()
    }

    /** 音质从高到低挑一个 */
    private fun pickQuality(qualities: List<String>): String {
        val order = listOf("flac24bit", "flac", "320k", "192k", "128k")
        order.forEach { q -> if (qualities.any { it.equals(q, true) }) return q }
        return qualities.first()
    }

    // ---------------- WebView ----------------

    private suspend fun prepare(
        context: Context,
        source: MusicSource,
        name: String,
        version: String
    ): LxInfo = withContext(Dispatchers.Main) {
        val script = runCatching { scriptFile(context, source.id).readText(Charsets.UTF_8) }.getOrNull()
            ?: return@withContext LxInfo(name, version, error = "脚本内容丢失，请重新添加")
        close(source.id)

        val webView = runCatching { WebView(context.applicationContext) }.getOrNull()
            ?: return@withContext LxInfo(name, version, error = "当前设备没有可用的 WebView")
        val bridge = Bridge(source.id, script)
        val ready = CompletableDeferred<Boolean>()
        bridge.readySignal = ready

        runCatching {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            @Suppress("DEPRECATION")
            webView.settings.allowUniversalAccessFromFileURLs = true
            webView.addJavascriptInterface(bridge, "Bridge")
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                    Log.d(TAG, "js: ${msg?.message()}")
                    return true
                }
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "page finished $url")
                }
            }
        }.onFailure {
            return@withContext LxInfo(name, version, error = "WebView 初始化失败：${it.message}")
        }

        val html = buildHtml(bridge, script)
        runCatching { webView.loadDataWithBaseURL("https://lx.local/", html, "text/html", "UTF-8", null) }
            .onFailure { return@withContext LxInfo(name, version, error = "脚本加载失败：${it.message}") }

        sessions[source.id] = Session(source.id, webView, bridge, ready)

        val inited = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!inited) {
            val reason = bridge.failure ?: "脚本没有完成初始化（可能被服务器拒绝或脚本已失效）"
            infos[source.id] = LxInfo(name, version, error = reason)
            return@withContext LxInfo(name, version, error = reason)
        }
        val platforms = bridge.platforms.toMap()
        val result = LxInfo(
            name = name.ifBlank { bridge.scriptName.ifBlank { "脚本源" } },
            version = version.ifBlank { bridge.scriptVersion },
            platforms = platforms
        )
        infos[source.id] = result
        result
    }

    private fun ensureSession(context: Context, source: MusicSource): Session? = sessions[source.id]

    fun close(id: Long) {
        sessions.remove(id)?.let { session ->
            runCatching {
                session.webView.removeJavascriptInterface("Bridge")
                session.webView.loadUrl("about:blank")
                session.webView.destroy()
            }
        }
        infos.remove(id)
    }

    fun closeAll() {
        sessions.keys.toList().forEach { close(it) }
    }

    // ---------------- 桥 ----------------

    private fun buildHtml(bridge: Bridge, script: String): String {
        val safeScript = script.replace("</script", "<\\/script")
        return """
            <!doctype html><html><head><meta charset="utf-8"></head><body>
            <script>
            window.__handlers = {};
            window.__rid = '';
            window.lx = {
              EVENT_NAMES: { request: 'request', inited: 'inited', updateAlert: 'updateAlert' },
              request: function (url, options, callback) {
                var opts = options || {};
                try {
                  var payload = opts.body == null ? ''
                    : (typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body));
                  var raw = Bridge.http((opts.method || 'GET'), url, JSON.stringify(opts.headers || {}), payload);
                  callback(null, JSON.parse(raw));
                } catch (e) {
                  callback(e, null);
                }
              },
              on: function (event, handler) { window.__handlers[event] = handler; },
              send: function (event, data) { Bridge.send(event, JSON.stringify(data || {})); },
              env: 'desktop',
              version: '2.0.0',
              currentScriptInfo: {
                name: '',
                uuid: '1',
                version: '1',
                get rawScript() { return Bridge.rawScript(); }
              },
              utils: {
                buffer: {
                  from: function (value, encoding) {
                    var hex = Bridge.bytesFromString(String(value == null ? '' : value), encoding || '');
                    return {
                      __hex: hex,
                      toString: function (enc) { return Bridge.stringFromHex(hex, enc || 'utf8'); },
                      length: hex.length / 2
                    };
                  },
                  bufToString: function (buf, enc) {
                    return Bridge.stringFromHex((buf && buf.__hex) || '', enc || 'utf8');
                  }
                },
                crypto: {
                  md5: function (value) { return Bridge.md5Hex(String(value), 0); },
                  hash: function (value, type) {
                    if (value && value.__hex) return Bridge.md5Hex(value.__hex, 1);
                    return Bridge.md5Hex(String(value), 0);
                  }
                }
              }
            };
            window.__callRequest = function (arg) {
              try {
                var handler = window.__handlers['request'];
                if (!handler) { Bridge.resolve(window.__rid, 0, '脚本没有注册 request 回调'); return; }
                var r = handler(arg);
                Promise.resolve(r).then(
                  function (v) { Bridge.resolve(window.__rid, 1, typeof v === 'string' ? v : JSON.stringify(v)); },
                  function (e) { Bridge.resolve(window.__rid, 0, String((e && e.message) || e)); }
                );
              } catch (e) {
                Bridge.resolve(window.__rid, 0, String((e && e.message) || e));
              }
            };
            try {
            $safeScript
            } catch (e) { Bridge.fail(String((e && e.message) || e)); }
            </script></body></html>
        """.trimIndent()
    }

    class Bridge(
        private val sourceId: Long,
        private val script: String
    ) {
        @Volatile var failure: String? = null
        @Volatile var scriptName: String = ""
        @Volatile var scriptVersion: String = ""
        val platforms = ConcurrentHashMap<String, List<String>>()

        @JavascriptInterface
        fun http(method: String, url: String, headersJson: String, payload: String): String {
            val response = JSONObject()
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method.ifBlank { "GET" }
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    runCatching {
                        val headers = JSONObject(headersJson)
                        headers.keys().forEach { key ->
                            val value = headers.optString(key)
                            if (value.isNotBlank()) setRequestProperty(key, value)
                        }
                    }
                    if (payload.isNotBlank() && this.requestMethod != "GET") {
                        doOutput = true
                        if (getRequestProperty("Content-Type").isNullOrBlank()) {
                            setRequestProperty("Content-Type", "application/json")
                        }
                    }
                }
                if (payload.isNotBlank() && connection.requestMethod != "GET") {
                    connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                }
                val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                val text = String(bytes, Charsets.UTF_8)
                // 脚本对 body 的用法分两类：版本 registry 要字符串（自己解析），
                // 取链接口要对象（直接读 body.code / body.data）。按来源分别给。
                val asString = url.contains("registry") || text.contains("\"vinfo\"")
                val parsed = if (asString) null else runCatching { JSONObject(text) }.getOrNull()
                    ?: runCatching { JSONArray(text) }.getOrNull()
                val body: Any = parsed ?: text
                JSONObject()
                    .put("statusCode", connection.responseCode)
                    .put("body", body)
                    .put("headers", JSONObject())
                    .toString()
            } catch (e: Exception) {
                JSONObject()
                    .put("statusCode", 0)
                    .put("body", "")
                    .put("error", e.message ?: e.javaClass.simpleName)
                    .put("headers", JSONObject())
                    .toString()
            } finally {
                runCatching { connection?.disconnect() }
            }
        }

        @JavascriptInterface
        fun send(event: String, dataJson: String) {
            if (event != "inited") return
            runCatching {
                val data = JSONObject(dataJson)
                val sources = data.optJSONObject("sources") ?: return
                platforms.clear()
                sources.keys().forEach { key ->
                    val node = sources.optJSONObject(key) ?: return@forEach
                    val qualities = node.optJSONArray("qualitys") ?: return@forEach
                    val list = (0 until qualities.length()).mapNotNull { qualities.optString(it).takeIf(String::isNotBlank) }
                    platforms[key] = list
                }
                scriptName = data.optString("name")
                scriptVersion = data.optString("version")
            }
            readySignal?.complete(true)
        }

        @JavascriptInterface
        fun rawScript(): String = script

        @JavascriptInterface
        fun resolve(id: String, ok: Int, payload: String) {
            pending[id]?.complete((ok == 1) to payload)
        }

        @JavascriptInterface
        fun fail(message: String) {
            failure = message
            readySignal?.complete(false)
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "script: $message")
        }

        @JavascriptInterface
        fun bytesFromString(value: String, encoding: String): String {
            val bytes = when (encoding.lowercase()) {
                "base64" -> runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull() ?: ByteArray(0)
                "hex" -> hexToBytes(value)
                else -> value.toByteArray(Charsets.UTF_8)
            }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        @JavascriptInterface
        fun stringFromHex(hex: String, encoding: String): String {
            val bytes = hexToBytes(hex)
            return when (encoding.lowercase()) {
                "base64" -> Base64.encodeToString(bytes, Base64.NO_WRAP)
                "hex" -> hex
                else -> String(bytes, Charsets.UTF_8)
            }
        }

        @JavascriptInterface
        fun md5Hex(value: String, isHex: Int): String {
            val bytes = if (isHex == 1) hexToBytes(value) else value.toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("MD5").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.filter { it.isLetterOrDigit() }
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        /** 由 prepare 注入，用来通知初始化完成 */
        var readySignal: CompletableDeferred<Boolean>? = null
    }
}
