package com.saltreplica.app

import android.app.Application
import androidx.media3.common.PlaybackException
import java.io.File

/** 应用入口：初始化偏好 + 记录崩溃堆栈，方便定位闪退 */
class App : Application() {

    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        MusicSourceStore.init(this)
        Accounts.init(this)
        runCatching { SleepTimer.restore() }
        runCatching {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                if (Prefs.themeIsDark()) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            )
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(filesDir, "last_crash.txt").writeText(
                    buildString {
                        appendLine(error.javaClass.name + ": " + error.message)
                        appendLine()
                        appendLine(error.stackTraceToString())
                    }
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

/** 播放失败提示：把 HTTP / 解析类错误翻成人话，别把一长串异常甩给用户 */
fun playbackErrorText(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
        "这首歌没有可用音频（可能需会员），换一首试试"

    else -> error.message?.take(160)?.ifBlank { null } ?: error.errorCodeName
}
