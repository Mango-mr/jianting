package com.saltreplica.app

import android.os.Handler
import android.os.Looper

/** 睡眠定时：到点暂停播放 */
object SleepTimer {

    private val handler = Handler(Looper.getMainLooper())

    private val stopTask = Runnable {
        PlaybackConnection.controller?.pause()
        Prefs.sleepUntil = 0L
    }

    fun schedule(minutes: Int) {
        handler.removeCallbacks(stopTask)
        if (minutes <= 0) {
            Prefs.sleepUntil = 0L
            return
        }
        val until = System.currentTimeMillis() + minutes * 60_000L
        Prefs.sleepUntil = until
        handler.postDelayed(stopTask, minutes * 60_000L)
    }

    /** 进程启动时恢复尚未到期的定时 */
    fun restore() {
        val until = Prefs.sleepUntil
        if (until <= 0L) return
        val left = until - System.currentTimeMillis()
        if (left <= 0L) {
            Prefs.sleepUntil = 0L
            return
        }
        handler.removeCallbacks(stopTask)
        handler.postDelayed(stopTask, left)
    }

    /** 剩余分钟，0 表示未开启 */
    fun remainMinutes(): Int {
        val until = Prefs.sleepUntil
        if (until <= 0L) return 0
        val left = until - System.currentTimeMillis()
        return if (left <= 0) 0 else ((left + 59_999L) / 60_000L).toInt()
    }
}
