package com.saltreplica.app

import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 扫描与缓存链路 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class)
class ScanTest {

    @Test
    fun fileScanFindsAudioFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(Environment.getExternalStorageDirectory(), "Music")
        dir.mkdirs()
        val mp3 = File(dir, "scan_probe.mp3")
        mp3.writeBytes(ByteArray(4096))
        val tracks = MusicRepository.query(context)
        assertTrue(
            "应扫描到刚写入的音频文件",
            tracks.any { it.path == mp3.absolutePath }
        )
    }

    @Test
    fun trackCacheRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = listOf(
            Track(
                id = 42,
                title = "测试歌曲",
                artist = "测试歌手",
                album = "测试专辑",
                durationMs = 180000,
                path = "/storage/emulated/0/Music/a.mp3",
                uri = Uri.parse("file:///storage/emulated/0/Music/a.mp3")
            )
        )
        TrackStore.save(context, source)
        val loaded = TrackStore.load(context)
        assertEquals(1, loaded.size)
        assertEquals("测试歌曲", loaded[0].titleSafe)
        assertEquals(source[0].path, loaded[0].path)
    }

    @Test
    fun trackFolderName() {
        val t = Track(
            id = 1, title = "x", artist = "", album = "",
            durationMs = 0, path = "/storage/emulated/0/Music/周杰伦/a.mp3"
        )
        assertEquals("周杰伦", t.folder)
        assertEquals("未知歌手", t.artistSafe)
    }
}
