package com.saltreplica.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 封面来源：MediaStore 的专辑封面优先；取不到时从音频文件里挖内嵌封面，
 * 解出来写进 cacheDir，供 Coil 直接加载。
 *
 * 列表滚动时会频繁请求，所以做了内存缓存 + 单曲去重 + 后台线程池。
 */
object CoverStore {

    private val mem = LruCache<Long, Uri>(200)
    private val inflight = ConcurrentHashMap<Long, Boolean>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** 同步拿「已经有了的」封面，没有就返回 null，不阻塞 */
    fun peek(track: Track): Uri? {
        track.albumArtUri?.let { return it }
        mem.get(track.id)?.let { uri ->
            if (uri.path?.let { File(it).exists() } == true) return uri
            mem.remove(track.id)
        }
        val cached = cacheFile(track)
        if (cached.exists() && cached.length() > 0) {
            val uri = Uri.fromFile(cached)
            mem.put(track.id, uri)
            return uri
        }
        return null
    }

    /** 异步补齐封面，拿到后回调（主线程） */
    fun request(track: Track, onReady: (Uri) -> Unit) {
        peek(track)?.let {
            onReady(it)
            return
        }
        if (inflight.putIfAbsent(track.id, true) != null) return
        pool.execute {
            runCatching {
                if (!track.path.isBlank()) {
                    val src = File(track.path)
                    if (src.exists() && src.canRead() && src.length() > 0) {
                        val tags = TagReader.read(src)
                        val pic = tags.picture
                        if (pic != null && pic.isNotEmpty()) {
                            val out = cacheFile(track)
                            out.parentFile?.mkdirs()
                            out.writeBytes(pic)
                        }
                    }
                }
            }
            inflight.remove(track.id)
            val cached = cacheFile(track)
            if (cached.exists() && cached.length() > 0) {
                val uri = Uri.fromFile(cached)
                mem.put(track.id, uri)
                main.post { onReady(uri) }
            }
        }
    }

    private fun cacheFile(track: Track): File {
        val dir = File(App.instance.cacheDir, "covers")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${track.id}.img")
    }
}
