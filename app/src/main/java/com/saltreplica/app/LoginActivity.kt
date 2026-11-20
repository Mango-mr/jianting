package com.saltreplica.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内置源账号登录：直接开对应官网的登录页，用户登完后读取 Cookie。
 * Cookie 只存在本机，用来换会员音质。
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SITE = "site"
        /** 网易云移动端登录页只在手机 UA 下成立 */
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        /** 酷我 / 酷狗的网页登录只在桌面站点里，手机 UA 会被跳到「打开 App」页 */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        fun start(context: Context, site: String) {
            context.startActivity(Intent(context, LoginActivity::class.java).putExtra(EXTRA_SITE, site))
        }
    }

    private lateinit var web: WebView
    private lateinit var tvTitle: TextView
    private lateinit var tvZoom: TextView
    private var site: String = Accounts.NETEASE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Accounts.init(this)
        site = intent.getStringExtra(EXTRA_SITE) ?: Accounts.NETEASE
        setContentView(R.layout.activity_login)

        tvTitle = findViewById(R.id.tv_title)
        tvTitle.text = when (site) {
            Accounts.KUWO -> "登录酷我"
            Accounts.KUGOU -> "登录酷狗"
            else -> "登录网易云"
        }
        findViewById<TextView>(R.id.tv_tip).text = when (site) {
            Accounts.KUWO -> "会自动点开酷我的「登录」弹窗；没弹出来就点页面右上角「登录」。可双指或点右下角放大"
            Accounts.KUGOU -> "酷狗提示「未实名」是账号侧限制，请先在酷狗 App 完成实名；本页只负责保存登录状态"
            else -> "登录后自动读取 Cookie；可双指或点右下角放大页面"
        }
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_done).setOnClickListener { finishLogin() }

        web = findViewById(R.id.web)
        web.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.bg))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = when (site) {
                Accounts.NETEASE -> MOBILE_UA
                else -> DESKTOP_UA
            }
            // 双指缩放 + 右下角的缩放按钮，两套都给
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        // 登录页跳转都留在 WebView 里，不再加 shouldOverrideUrlLoading，
        // 避免个别站点在拦截回调里反复重载造成「一直闪」。
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 酷我没有独立登录页，登录入口是首页右上角的「登录」，这里自动点开它
                if (site == Accounts.KUWO) autoOpenKuwoLogin()
            }

            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                super.onScaleChanged(view, oldScale, newScale)
                updateZoomLabel(newScale)
            }
        }
        tvZoom = findViewById(R.id.tv_zoom)
        findViewById<TextView>(R.id.btn_zoom_in).setOnClickListener { web.zoomIn() }
        findViewById<TextView>(R.id.btn_zoom_out).setOnClickListener { web.zoomOut() }
        tvZoom.setOnClickListener {
            // 点百分比回到「适应屏幕」的初始缩放
            web.loadUrl(web.url ?: return@setOnClickListener)
        }
        web.loadUrl(
            when (site) {
                Accounts.KUWO -> "https://www.kuwo.cn/"
                Accounts.KUGOU -> "https://passport.kugou.com/login"
                else -> "https://y.music.163.com/m/login"
            }
        )
    }

    /** 在酷我首页里找「登录」并点开登录弹层；找不到就算了，用户还能自己点 */
    private fun autoOpenKuwoLogin() {
        val js = """
            (function () {
              var tries = 0;
              var timer = setInterval(function () {
                tries++;
                var nodes = document.querySelectorAll('a,button,span,div');
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  var txt = (el.textContent || '').trim();
                  if (txt !== '登录' && txt !== '登录/注册' && txt !== '立即登录') continue;
                  var rect = el.getBoundingClientRect();
                  if (rect.width <= 0 || rect.width > 220 || rect.height <= 0 || rect.height > 70) continue;
                  el.click();
                  clearInterval(timer);
                  return;
                }
                if (tries > 30) clearInterval(timer);
              }, 400);
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun updateZoomLabel(scale: Float) {
        val percent = (scale * 100).toInt().coerceIn(30, 400)
        tvZoom.text = "$percent%"
    }

    private fun finishLogin() {
        CookieManager.getInstance().flush()
        val cookie = when (site) {
            Accounts.KUWO -> CookieManager.getInstance().getCookie("https://www.kuwo.cn").orEmpty()
            Accounts.KUGOU -> listOf(
                CookieManager.getInstance().getCookie("https://www.kugou.com").orEmpty(),
                CookieManager.getInstance().getCookie("https://m.kugou.com").orEmpty()
            ).filter { it.isNotBlank() }.joinToString("; ")
            else -> listOf(
                CookieManager.getInstance().getCookie("https://music.163.com").orEmpty(),
                CookieManager.getInstance().getCookie("https://y.music.163.com").orEmpty()
            ).filter { it.isNotBlank() }.joinToString("; ")
        }
        val ok = when (site) {
            Accounts.KUWO -> cookie.contains("kw_token=") || cookie.contains("userid=")
            Accounts.KUGOU -> cookie.contains("kugouID=") || cookie.contains("KuMing=") ||
                cookie.contains("token=")
            else -> cookie.contains("MUSIC_U=")
        }
        if (!ok) {
            Tip.show(this, "还没检测到登录，请先在页面里登完账号再点完成")
            return
        }
        val nick = if (site == Accounts.NETEASE) fetchNeteaseNick(cookie) else ""
        Accounts.save(site, cookie, nick)
        Tip.show(this, if (nick.isNotBlank()) "已登录：$nick" else "已登录，Cookie 已保存")
        setResult(RESULT_OK)
        finish()
    }

    /** 网易云登录后查一下昵称，用来确认 Cookie 真的有效 */
    private fun fetchNeteaseNick(cookie: String): String = runCatching {
        val connection = (URL("https://music.163.com/api/nuser/account/get").openConnection() as HttpURLConnection)
            .apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", MOBILE_UA)
                setRequestProperty("Referer", "https://music.163.com/")
                setRequestProperty("Cookie", cookie)
            }
        val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        connection.disconnect()
        JSONObject(body).optJSONObject("profile")?.optString("nickname").orEmpty()
    }.getOrDefault("")

    override fun onDestroy() {
        runCatching {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }
}
