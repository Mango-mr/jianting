package com.saltreplica.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 歌词解析 / 内嵌与外挂歌词读取 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class)
class LyricsTest {

    private val tmpDirs = mutableListOf<File>()

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "lyr-${System.nanoTime()}")
        dir.mkdirs()
        tmpDirs.add(dir)
        return dir
    }

    @After
    fun cleanup() {
        tmpDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    @Test
    fun parsesTimestampedLrc() {
        val lines = LyricsParser.parse("[00:01.50]第一句\n[00:12.345]第二句")
        assertEquals(2, lines.size)
        assertEquals(1500L, lines[0].timeMs)
        assertEquals("第一句", lines[0].text)
        assertEquals(12345L, lines[1].timeMs)
    }

    @Test
    fun parsesMultiTimestampLine() {
        val lines = LyricsParser.parse("[00:10.00][01:20.00]重复句")
        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].timeMs)
        assertEquals(80_000L, lines[1].timeMs)
        assertEquals("重复句", lines[0].text)
    }

    @Test
    fun plainTextHasNoTimeline() {
        assertTrue(LyricsParser.parse("这是一段没有时间戳的歌词\n第二行").isEmpty())
    }

    @Test
    fun unsyncedDistributesOverDuration() {
        val text = (1..10).joinToString("\n") { "第 $it 行" }
        val lines = LyricsParser.unsynced(text, 60_000)
        assertEquals(10, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertEquals(6_000L, lines[1].timeMs)
        assertEquals(54_000L, lines.last().timeMs)
    }

    @Test
    fun unsyncedFallsBackWithInvalidDuration() {
        val lines = LyricsParser.unsynced("甲\n乙", 0)
        assertEquals(2, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertTrue(lines[1].timeMs >= 1_200L)
    }

    @Test
    fun readsSidecarLrcFile() {
        val dir = tempDir()
        val audio = File(dir, "song.mp3").apply { writeBytes(ByteArray(16)) }
        File(dir, "song.lrc").writeBytes("[00:03.00]外挂歌词".toByteArray(Charsets.UTF_8))
        val lrc = TagReader.readSidecarLrc(audio)
        assertNotNull(lrc)
        assertTrue(lrc!!.contains("外挂歌词"))
    }
}
