package com.saltreplica.app

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player

/** 轻量本地偏好 */
object Prefs {
    private const val NAME = "salt_prefs"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    /** 播放模式：0 顺序，1 随机，2 单曲循环 */
    var playMode: Int
        get() = sp.getInt("play_mode", Player.REPEAT_MODE_OFF)
        set(value) = sp.edit().putInt("play_mode", value).apply()

    var lastTrackId: Long
        get() = sp.getLong("last_track_id", -1L)
        set(value) = sp.edit().putLong("last_track_id", value).apply()

    /**
     * 主题：0 奶白，1 深灰，2 霓虹紫，3 暗橙，4 荧光黄
     * 0/1 沿用系统明暗；2/3/4 都是深底 + 各自强调色。
     */
    var themeMode: Int
        get() = sp.getInt("theme_mode", 0)
        set(value) = sp.edit().putInt("theme_mode", value).apply()

    /** 该主题是否强制深色 */
    fun themeIsDark(mode: Int = themeMode): Boolean = mode >= 1

    /** 均衡器开关 */
    var eqEnabled: Boolean
        get() = sp.getBoolean("eq_enabled", false)
        set(value) = sp.edit().putBoolean("eq_enabled", value).apply()

    /** 均衡器预设索引，-1 表示自定义 */
    var eqPreset: Int
        get() = sp.getInt("eq_preset", 0)
        set(value) = sp.edit().putInt("eq_preset", value).apply()

    /** 自定义全场曲线：5 个锚点 dB，逗号分隔 */
    var eqBands: FloatArray
        get() = sp.getString("eq_bands", "0,0,0,0,0")
            ?.split(',')
            ?.mapNotNull { it.trim().toFloatOrNull() }
            ?.takeIf { it.size == 5 }
            ?.toFloatArray()
            ?: floatArrayOf(0f, 0f, 0f, 0f, 0f)
        set(value) = sp.edit().putString("eq_bands", value.joinToString(",")).apply()

    /** 播放速度（设置页已移除入口，保留以兼容播放服务读取） */
    var speed: Float
        get() = sp.getFloat("play_speed", 1f)
        set(value) = sp.edit().putFloat("play_speed", value).apply()

    /** 睡眠定时结束时间戳，0 表示关闭 */
    var sleepUntil: Long
        get() = sp.getLong("sleep_until", 0L)
        set(value) = sp.edit().putLong("sleep_until", value).apply()

    /** 启动时自动扫描 */
    var autoScan: Boolean
        get() = sp.getBoolean("auto_scan", true)
        set(value) = sp.edit().putBoolean("auto_scan", value).apply()

    /** 排序：0 标题，1 歌手，2 时长，3 添加时间 */
    var sortMode: Int
        get() = sp.getInt("sort_mode", 0)
        set(value) = sp.edit().putInt("sort_mode", value).apply()

    // ---------------- 选歌条件（扫描筛选用，0 表示不限） ----------------

    /** 最短时长，秒 */
    var scanMinSec: Int
        get() = sp.getInt("scan_min_sec", 0)
        set(value) = sp.edit().putInt("scan_min_sec", value).apply()

    /** 最长时长，秒；0 表示不限 */
    var scanMaxSec: Int
        get() = sp.getInt("scan_max_sec", 0)
        set(value) = sp.edit().putInt("scan_max_sec", value).apply()

    /** 最小文件体积，MB */
    var scanMinMb: Int
        get() = sp.getInt("scan_min_mb", 0)
        set(value) = sp.edit().putInt("scan_min_mb", value).apply()

    /** 最大文件体积，MB；0 表示不限 */
    var scanMaxMb: Int
        get() = sp.getInt("scan_max_mb", 0)
        set(value) = sp.edit().putInt("scan_max_mb", value).apply()

    /** 只收标题和歌手都不为空的歌曲 */
    var scanRequireTag: Boolean
        get() = sp.getBoolean("scan_require_tag", false)
        set(value) = sp.edit().putBoolean("scan_require_tag", value).apply()

    // ---------------- 歌词外观 ----------------

    /** 歌词颜色索引：0 主题色，1 墨黑，2 纯白，3 暖橙，4 青，5 粉 */
    var lyricsColor: Int
        get() = sp.getInt("lyrics_color", 0)
        set(value) = sp.edit().putInt("lyrics_color", value).apply()

    /** 歌词字号（普通行），sp */
    var lyricsSize: Int
        get() = sp.getInt("lyrics_size", 15)
        set(value) = sp.edit().putInt("lyrics_size", value.coerceIn(12, 26)).apply()

    // ---------------- 播放页背景 ----------------

    /** 用当前歌曲封面做播放页背景 */
    var coverBackground: Boolean
        get() = sp.getBoolean("cover_bg", false)
        set(value) = sp.edit().putBoolean("cover_bg", value).apply()

    /** 背景模糊程度 0..25 */
    var coverBlur: Int
        get() = sp.getInt("cover_blur", 12).coerceIn(0, 25)
        set(value) = sp.edit().putInt("cover_blur", value.coerceIn(0, 25)).apply()

    /** 从曲库移除的歌曲 id：只从列表隐藏，不删除手机里的文件 */
    private val hiddenIds: Set<String>
        get() = sp.getStringSet("hidden_ids", emptySet()) ?: emptySet()

    fun isHidden(id: Long): Boolean = id.toString() in hiddenIds

    fun hide(id: Long) {
        sp.edit().putStringSet("hidden_ids", hiddenIds + id.toString()).apply()
    }

    fun hideAll(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        sp.edit().putStringSet("hidden_ids", hiddenIds + ids.map { it.toString() }).apply()
    }
}
