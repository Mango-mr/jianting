package com.saltreplica.app

import android.content.Context

/** 歌词外观：颜色跟随设置，字号跟随设置 */
object LyricsStyle {

    /** 0 号为主题色（运行时读 attr），其余为固定色 */
    val colors = intArrayOf(
        0,
        0xFF23211E.toInt(),
        0xFFFFFFFF.toInt(),
        0xFFFF8A5C.toInt(),
        0xFF5BC8B8.toInt(),
        0xFFFF8FB1.toInt()
    )

    fun color(context: Context): Int = color(context, Prefs.lyricsColor)

    /** 取第 index 个颜色；0 号为主题色，其余为固定色 */
    fun color(context: Context, index: Int): Int {
        val i = index.coerceIn(0, colors.lastIndex)
        return if (i == 0) SettingsUi.primary(context) else colors[i]
    }

    /** 高亮行的字号比普通行大 3sp */
    fun focusSize(): Float = (Prefs.lyricsSize + 3).toFloat()

    fun normalSize(): Float = Prefs.lyricsSize.toFloat()
}
