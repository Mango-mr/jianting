package com.saltreplica.app

import android.media.audiofx.Equalizer

/**
 * 均衡器。与播放服务同进程，直接绑定 ExoPlayer 的 audioSessionId。
 * 部分设备（含模拟器）可能不支持，所有调用都做保护。
 */
object EqController {

    val presetNames = arrayOf("标准", "流行", "摇滚", "爵士", "古典", "电子", "人声")

    /** 5 个频段锚点（低频 → 高频），单位 dB */
    private val curves = arrayOf(
        floatArrayOf(0f, 0f, 0f, 0f, 0f),
        floatArrayOf(-2f, 0f, 4f, 2f, 0f),
        floatArrayOf(4f, 2f, -2f, 2f, 4f),
        floatArrayOf(2f, 1f, 0f, 2f, 3f),
        floatArrayOf(0f, 1f, 2f, 1f, 0f),
        floatArrayOf(5f, 2f, -1f, 2f, 4f),
        floatArrayOf(-3f, 2f, 5f, 3f, -2f)
    )

    private var eq: Equalizer? = null
    private var attachedSession = 0
    private var supported = true

    val isSupported: Boolean get() = supported

    fun attachIfNeeded(audioSessionId: Int) {
        if (!supported) return
        if (audioSessionId == 0) return
        if (eq != null && attachedSession == audioSessionId) return
        release()
        try {
            val e = Equalizer(0, audioSessionId)
            val range = e.bandLevelRange
            // 有些设备创建出来就没有有效频段
            if (e.numberOfBands <= 0) {
                e.release()
                supported = false
                return
            }
            eq = e
            attachedSession = audioSessionId
            e.enabled = Prefs.eqEnabled
            if (Prefs.eqPreset < 0) applyCurve(Prefs.eqBands)
            else applyPreset(Prefs.eqPreset, range)
        } catch (_: Throwable) {
            supported = false
        }
    }

    fun setEnabled(enabled: Boolean) {
        Prefs.eqEnabled = enabled
        try {
            eq?.enabled = enabled
        } catch (_: Throwable) {
        }
    }

    fun applyPreset(index: Int) {
        Prefs.eqPreset = index
        val e = eq ?: return
        applyPreset(index, e.bandLevelRange)
    }

    /** 自定义调节：把某个锚点（0 低频 … 4 高频）改到 db，其余保持 */
    fun setCustomBand(index: Int, db: Float) {
        if (index !in 0..4) return
        val curve = Prefs.eqBands.copyOf()
        curve[index] = db.coerceIn(-12f, 12f)
        Prefs.eqBands = curve
        Prefs.eqPreset = -1
        applyCurve(curve)
    }

    /** 当前生效的 5 点曲线（自定义时取用户曲线，否则取预设） */
    fun currentCurve(): FloatArray {
        val preset = Prefs.eqPreset
        return if (preset in curves.indices) curves[preset].copyOf() else Prefs.eqBands.copyOf()
    }

    fun applyCurve(curve: FloatArray) {
        val e = eq ?: return
        val minLevel = e.bandLevelRange?.getOrNull(0)?.toInt() ?: -1500
        val maxLevel = e.bandLevelRange?.getOrNull(1)?.toInt() ?: 1500
        writeCurve(e, curve, minLevel, maxLevel)
    }

    private fun applyPreset(index: Int, range: ShortArray?) {
        val e = eq ?: return
        val curve = curves.getOrNull(index) ?: curves[0]
        val minLevel = range?.getOrNull(0)?.toInt() ?: -1500
        val maxLevel = range?.getOrNull(1)?.toInt() ?: 1500
        writeCurve(e, curve, minLevel, maxLevel)
    }

    private fun writeCurve(e: Equalizer, curve: FloatArray, minLevel: Int, maxLevel: Int) {
        try {
            for (band in 0 until e.numberOfBands) {
                val t = if (e.numberOfBands <= 1) 0f
                else band.toFloat() / (e.numberOfBands - 1).toFloat()
                val db = interpolate(curve, t)
                val milliBel = (db * 100).toInt().coerceIn(minLevel, maxLevel)
                e.setBandLevel(band.toShort(), milliBel.toShort())
            }
        } catch (_: Throwable) {
        }
    }

    /** 在 5 点曲线上按 0..1 位置线性插值 */
    private fun interpolate(curve: FloatArray, t: Float): Float {
        val pos = t.coerceIn(0f, 1f) * (curve.size - 1)
        val i = pos.toInt().coerceAtMost(curve.size - 2)
        val frac = pos - i
        return curve[i] * (1 - frac) + curve[i + 1] * frac
    }

    fun release() {
        try {
            eq?.release()
        } catch (_: Throwable) {
        }
        eq = null
        attachedSession = 0
    }
}
