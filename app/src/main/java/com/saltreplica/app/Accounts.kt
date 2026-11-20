package com.saltreplica.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 内置搜索源的账号与开关。
 *
 * · 网易云：WebView 登录后拿 MUSIC_U 等 Cookie，换链时带上 Cookie 就能拿到 VIP 音质
 * · 酷我：登录后拿 kw_token，换链接口要同时带 Cookie 和 csrf 头
 * · 酷狗：登录后带上 Cookie 再换链，免费曲目本来就能出直链
 */
object Accounts {

    const val NETEASE = "netease"
    const val KUWO = "kuwo"
    const val KUGOU = "kugou"

    private lateinit var sp: SharedPreferences
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        sp = context.applicationContext.getSharedPreferences("accounts", Context.MODE_PRIVATE)
        initialized = true
    }

    fun cookie(site: String): String =
        if (initialized) sp.getString("cookie_$site", "").orEmpty() else ""

    fun nickname(site: String): String =
        if (initialized) sp.getString("nick_$site", "").orEmpty() else ""

    fun save(site: String, cookie: String, nickname: String = "") {
        if (!initialized || cookie.isBlank()) return
        sp.edit()
            .putString("cookie_$site", cookie)
            .putString("nick_$site", nickname.ifBlank { nickname(site) })
            .apply()
    }

    fun clear(site: String) {
        if (!initialized) return
        sp.edit().remove("cookie_$site").remove("nick_$site").apply()
    }

    fun loggedIn(site: String): Boolean = when (site) {
        NETEASE -> cookie(site).contains("MUSIC_U=")
        KUWO -> cookie(site).contains("kw_token=") || cookie(site).contains("userid=")
        KUGOU -> cookie(site).contains("KuMing=") || cookie(site).contains("kugouID=") ||
            cookie(site).contains("token=")
        else -> false
    }

    /** 该内置源是否参与搜索 / 换链 */
    fun providerEnabled(site: String): Boolean =
        if (!initialized) true else sp.getBoolean("provider_$site", true)

    fun toggleProvider(site: String) {
        if (!initialized) return
        sp.edit().putBoolean("provider_$site", !providerEnabled(site)).apply()
    }

    /** 给某站点的请求挑要带的 Cookie；没登录就返回空串 */
    fun cookieForHost(host: String): String {
        val site = when {
            host.contains("163.com") || host.contains("126.net") -> NETEASE
            host.contains("kuwo.cn") -> KUWO
            host.contains("kugou.com") -> KUGOU
            else -> return ""
        }
        return if (loggedIn(site)) cookie(site) else ""
    }

    fun kuwoToken(): String {
        val raw = cookie(KUWO)
        Regex("kw_token=([^;]+)").find(raw)?.let { return it.groupValues[1].trim() }
        return ""
    }
}
