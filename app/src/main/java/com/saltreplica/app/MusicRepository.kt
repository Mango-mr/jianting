package com.saltreplica.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 音乐扫描：MediaStore + 文件系统双通道。
 * 只查 MediaStore 在部分设备上会漏（媒体库未索引 / IS_MUSIC 为 0），
 * 所以再遍历一次存储目录做兜底。
 */
object MusicRepository {

    /**
     * 扫描时的选歌条件。0 一律表示"不限"。
     * 时长/体积未知（读不出元数据）的条目不会被这两项筛掉，避免误杀。
     */
    data class ScanFilter(
        val minDurationMs: Long = 0,
        val maxDurationMs: Long = 0,
        val minSize: Long = 0,
        val maxSize: Long = 0,
        val requireTag: Boolean = false
    ) {
        val isDefault: Boolean
            get() = minDurationMs == 0L && maxDurationMs == 0L &&
                    minSize == 0L && maxSize == 0L && !requireTag

        fun matches(track: Track): Boolean {
            if (track.durationMs > 0) {
                if (minDurationMs > 0 && track.durationMs < minDurationMs) return false
                if (maxDurationMs > 0 && track.durationMs > maxDurationMs) return false
            }
            if (track.size > 0) {
                if (minSize > 0 && track.size < minSize) return false
                if (maxSize > 0 && track.size > maxSize) return false
            }
            if (requireTag && (track.title.isBlank() || track.artist.isBlank())) return false
            return true
        }

        companion object {
            fun fromPrefs(): ScanFilter = ScanFilter(
                minDurationMs = Prefs.scanMinSec * 1000L,
                maxDurationMs = Prefs.scanMaxSec * 1000L,
                minSize = Prefs.scanMinMb * 1024L * 1024L,
                maxSize = Prefs.scanMaxMb * 1024L * 1024L,
                requireTag = Prefs.scanRequireTag
            )
        }
    }

    private val AUDIO_EXT = setOf(
        "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "wma", "amr", "ape", "m4b", "mka"
    )

    private val SKIP_DIRS = setOf(
        "Android", "LOST.DIR", ".thumbnails", ".trashed", "cache", "Cache"
    )

    suspend fun queryAsync(context: Context, filter: ScanFilter = ScanFilter.fromPrefs()): List<Track> =
        withContext(Dispatchers.IO) {
            query(context, filter)
        }

    fun query(context: Context, filter: ScanFilter? = null): List<Track> {
        val fromStore = queryMediaStore(context)
        val known = HashSet<String>(fromStore.size)
        fromStore.forEach { if (it.path.isNotEmpty()) known.add(it.path) }
        val fromFiles = scanFiles(context, known)
        val all = ArrayList<Track>(fromStore.size + fromFiles.size)
        all.addAll(fromStore)
        all.addAll(fromFiles)
        // 双通道可能重复收同一首歌（MediaStore 未给出路径时），按 文件名+大小 再去重一次
        val dedup = LinkedHashMap<String, Track>()
        for (track in all) {
            val key = if (track.path.isNotEmpty()) track.path
            else track.titleSafe.lowercase() + "@" + track.size
            if (!dedup.containsKey(key)) dedup[key] = track
        }
        val result = ArrayList<Track>(dedup.size)
        for (track in dedup.values) {
            if (filter == null || filter.matches(track)) result.add(track)
        }
        return result.sortedBy { it.titleSafe.lowercase() }
    }

    // ---------------- MediaStore ----------------

    private fun queryMediaStore(context: Context): List<Track> {
        val list = ArrayList<Track>(512)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        // 不再用 IS_MUSIC != 0：微信/浏览器下载的音频常常 IS_MUSIC=0，会被整批漏掉
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                val idxId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val idxTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val idxArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val idxAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val idxDuration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val idxPath = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val idxAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val idxSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val idxDate = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val idxName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

                while (c.moveToNext()) {
                    val duration = c.getLong(idxDuration)
                    val size = c.getLong(idxSize)
                    if (size in 1..100) continue
                    val path = c.getString(idxPath) ?: ""
                    val name = c.getString(idxName) ?: ""
                    if (!isAudioName(name) && !isAudioName(path)) continue
                    // 只滤掉极短的提示音，其余（含时长未知的）都收下
                    if (duration in 1..800) continue
                    var title = c.getString(idxTitle) ?: ""
                    if (title.isBlank()) title = name.substringBeforeLast('.')
                    list.add(
                        Track(
                            id = c.getLong(idxId),
                            title = title,
                            artist = c.getString(idxArtist) ?: "",
                            album = c.getString(idxAlbum) ?: "",
                            durationMs = duration,
                            path = path,
                            albumId = c.getLong(idxAlbumId),
                            size = size,
                            dateAdded = c.getLong(idxDate)
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun isAudioName(name: String): Boolean {
        if (name.isBlank()) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXT
    }

    // ---------------- 文件遍历 ----------------

    private fun scanFiles(context: Context, known: Set<String>): List<Track> {
        val result = ArrayList<Track>()
        val roots = ArrayList<File>(4)
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { roots.add(it) }
        runCatching { context.getExternalFilesDir(null) }.getOrNull()?.parentFile?.parentFile?.let {
            if (it !in roots) roots.add(it)
        }

        val mmr = MediaMetadataRetriever()
        var visited = 0
        val deadline = android.os.SystemClock.elapsedRealtime() + 35_000L
        try {
            for (root in roots) {
                if (!root.exists() || !root.canRead()) continue
                val queue = ArrayDeque<Pair<File, Int>>()
                queue.add(root to 0)
                while (queue.isNotEmpty() && visited < 20000) {
                    if (android.os.SystemClock.elapsedRealtime() > deadline) break
                    val (dir, depth) = queue.removeFirst()
                    if (depth > 6) continue
                    val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
                    for (child in children) {
                        visited++
                        if (visited > 20000) break
                        val name = child.name
                        if (name.startsWith(".")) continue
                        if (child.isDirectory) {
                            if (name in SKIP_DIRS) continue
                            queue.add(child to depth + 1)
                            continue
                        }
                        if (!isAudioName(name)) continue
                        val path = child.absolutePath
                        if (path in known) continue
                        if (child.length() <= 100) continue
                        val meta = readMeta(mmr, path)
                        if (meta != null && meta.durationMs in 1..800) continue
                        result.add(Track.fromFile(child, meta))
                    }
                }
            }
        } catch (_: Throwable) {
            // 忽略遍历异常，返回已扫到的部分
        } finally {
            runCatching { mmr.release() }
        }
        return result
    }

    private fun readMeta(mmr: MediaMetadataRetriever, path: String): TrackMeta? {
        return try {
            mmr.setDataSource(path)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            TrackMeta(title, artist, album, duration)
        } catch (_: Throwable) {
            null
        }
    }
}

/** 当前扫描结果的轻量缓存，供播放页查询歌曲信息 */
object TrackCache {
    @Volatile
    var songs: List<Track> = emptyList()

    fun findById(id: Long): Track? = songs.firstOrNull { it.id == id }
}
