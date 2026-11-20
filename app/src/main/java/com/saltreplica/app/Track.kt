package com.saltreplica.app

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.TimeUnit

/** 一首本地歌曲 */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val path: String,
    val albumId: Long = 0,
    val size: Long = 0,
    val dateAdded: Long = 0,
    val uri: Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
) {
    val titleSafe: String get() = title.trim().ifBlank { "未知歌曲" }
    val artistSafe: String get() = artist.trim().ifBlank { "未知歌手" }
    val albumSafe: String get() = album.trim().ifBlank { "未知专辑" }

    /** 所在文件夹名 */
    val folder: String
        get() = path.substringBeforeLast('/', "").substringAfterLast('/', "").ifBlank { "根目录" }

    /** 实际播放地址：优先内容 URI（Scoped Storage 下必定可读） */
    val contentUri: Uri
        get() = uri

    val albumArtUri: Uri?
        get() = if (albumId > 0) {
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
        } else null

    /** 从文件系统扫描到的条目：稳定负数 id，避免与 MediaStore id 冲突 */
    companion object {
        fun fromFile(file: File, meta: TrackMeta?, dateAdded: Long = 0L): Track {
            val path = file.absolutePath
            val id = -(path.hashCode().toLong() and 0x7fffffffL) - 1_000_000L
            return Track(
                id = id,
                title = meta?.title?.ifBlank { null } ?: file.nameWithoutExtension,
                artist = meta?.artist.orEmpty(),
                album = meta?.album.orEmpty(),
                durationMs = meta?.durationMs ?: 0L,
                path = path,
                albumId = 0,
                size = file.length(),
                dateAdded = dateAdded,
                uri = Uri.fromFile(file)
            )
        }
    }
}

/** MediaMetadataRetriever 读出的元数据 */
data class TrackMeta(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long
)

/** 时长格式化 mm:ss */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format("%02d:%02d", m, s)
}
