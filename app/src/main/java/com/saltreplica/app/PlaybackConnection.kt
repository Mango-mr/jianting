package com.saltreplica.app

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * 全局播放器连接（主线程使用）。
 * MediaController 到 MediaSessionService 的绑定是异步的，之前没连上就静默失败，
 * 表现就是"点了歌曲没反应"。这里补上排队等待 + 失败重试 + 错误原因记录。
 */
object PlaybackConnection {

    private var future: ListenableFuture<MediaController>? = null
    private var attempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(MediaController?) -> Unit>()
    private val waiters = mutableListOf<(MediaController?) -> Unit>()

    var controller: MediaController? = null
        private set

    /** 最近一次连接失败的原因，界面可直接展示 */
    var lastError: String? = null
        private set

    /** 单测环境下可关闭自动连接（Robolectric 无法真正绑定 MediaSessionService） */
    var autoConnect = true

    /** 测试/调试用：每次收到播放请求时回调一次 */
    var requestObserver: ((Context) -> Unit)? = null

    fun connect(context: Context, force: Boolean = false) {
        if (!autoConnect) return
        if (!force && (controller != null || future != null)) return
        val app = context.applicationContext
        val f = try {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            MediaController.Builder(app, token).buildAsync()
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            retryLater(app)
            return
        }
        future = f
        f.addListener({
            future = null
            val c = try {
                f.get()
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                null
            }
            if (c != null) {
                controller = c
                attempts = 0
                lastError = null
                listeners.forEach { it(c) }
                flush(c)
            } else {
                retryLater(app)
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun retryLater(app: Context) {
        if (attempts >= 3) {
            attempts = 0
            flush(null)
            return
        }
        attempts++
        mainHandler.postDelayed({ connect(app, force = true) }, 1200L * attempts)
    }

    private fun flush(c: MediaController?) {
        val copy = waiters.toList()
        waiters.clear()
        copy.forEach { it(c) }
    }

    /**
     * 需要立刻播放时用它：已连接直接回调；未连接则自动连接并排队等待，
     * 6 秒仍未连上回调 null，由调用方提示用户。
     */
    fun request(context: Context, callback: (MediaController?) -> Unit) {
        runCatching { requestObserver?.invoke(context) }
        val c = controller
        if (c != null) {
            callback(c)
            return
        }
        waiters.add(callback)
        connect(context)
        mainHandler.postDelayed({
            if (waiters.remove(callback)) callback(null)
        }, 6000L)
    }

    fun addListener(listener: (MediaController?) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
        listener(controller)
    }

    fun removeListener(listener: (MediaController?) -> Unit) {
        listeners.remove(listener)
    }

    /** 应用播放模式偏好 */
    fun applyMode(c: MediaController) {
        when (Prefs.playMode) {
            1 -> {
                c.shuffleModeEnabled = true
                c.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            }
            2 -> {
                c.shuffleModeEnabled = false
                c.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
            }
            else -> {
                c.shuffleModeEnabled = false
                c.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
            }
        }
    }
}
