package com.saltreplica.app

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * 从音频文件本身读取「内嵌歌词」与「内嵌封面」。
 *
 * 支持：
 *  - MP3 / ID3v2.3 / ID3v2.4：USLT（无同步歌词）、APIC（封面）
 *  - FLAC：VORBIS_COMMENT 里的 LYRICS / UNSYNCEDLYRICS，PICTURE 块
 *  - 同名 .lrc 外挂歌词（UTF-8 / GBK 自动判断）
 *
 * 不做 MP4/M4A 的 ©lyr（atom 结构复杂，本地曲库占比很低），
 * 这类文件仍会走在线歌词兜底。
 */
object TagReader {

    private const val TAG = "TagReader"
    private const val MAX_FRAME = 16 * 1024 * 1024

    data class Tags(val lyrics: String?, val picture: ByteArray?)

    fun read(file: File): Tags {
        if (!file.exists() || !file.canRead()) return Tags(null, null)
        return runCatching { readUnsafe(file) }
            .getOrElse {
                Log.w(TAG, "read fail ${file.name}: ${it.message}")
                Tags(null, null)
            }
    }

    private fun readUnsafe(file: File): Tags {
        val head = ByteArray(4)
        RandomAccessFile(file, "r").use { raf ->
            if (raf.read(head) < 4) return Tags(null, null)
        }
        return when {
            head[0].toInt() == 0x49 && head[1].toInt() == 0x44 && head[2].toInt() == 0x33 -> parseId3(file)
            head[0].toInt() == 0x66 && head[1].toInt() == 0x4C &&
                    head[2].toInt() == 0x61 && head[3].toInt() == 0x43 -> parseFlac(file)
            else -> Tags(null, null)
        }
    }

    // ---------------- MP3 / ID3 ----------------

    private fun parseId3(file: File): Tags {
        RandomAccessFile(file, "r").use { raf ->
            val hdr = ByteArray(10)
            if (raf.read(hdr) < 10) return Tags(null, null)
            val major = hdr[3].toInt()
            if (major != 3 && major != 4) return Tags(null, null)
            val flags = hdr[5].toInt() and 0xff
            val tagSize = syncSafe(hdr, 6)
            if (tagSize <= 0) return Tags(null, null)

            var pos = 10L
            val end = 10L + tagSize
            // 扩展头
            if ((flags and 0x40) != 0) {
                val ext = ByteArray(4)
                if (raf.read(ext) < 4) return Tags(null, null)
                val extSize = if (major == 4) syncSafe(ext, 0) else beInt(ext, 0)
                pos = if (major == 4) 10L + extSize else 10L + 4L + extSize
            }

            var lyrics: String? = null
            var picture: ByteArray? = null
            val frameHdr = ByteArray(10)

            while (pos + 10 <= end) {
                raf.seek(pos)
                if (raf.read(frameHdr) < 10) break
                val id = String(frameHdr, 0, 4, Charsets.ISO_8859_1)
                if (id.isBlank() || id[0] == '\u0000' || !id.all { it.isLetterOrDigit() }) break
                val size = if (major == 4) syncSafe(frameHdr, 4) else beInt(frameHdr, 4)
                if (size <= 0 || size > MAX_FRAME) break
                if (pos + 10 + size > end) break
                val body = ByteArray(size)
                if (raf.read(body) < size) break
                when (id) {
                    "USLT" -> if (lyrics == null) lyrics = decodeUslt(body)
                    "APIC" -> if (picture == null) picture = decodeApic(body)
                }
                pos += 10L + size
            }
            return Tags(lyrics, picture)
        }
    }

    private fun decodeUslt(b: ByteArray): String? {
        if (b.size < 4) return null
        val enc = b[0].toInt() and 0xff
        var i = 4 // 跳过 enc + 3 字节语言
        i = skipTerminated(b, i, enc)
        if (i >= b.size) return null
        return decode(b, i, b.size, enc)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun decodeApic(b: ByteArray): ByteArray? {
        if (b.size < 4) return null
        val enc = b[0].toInt() and 0xff
        var i = 1
        while (i < b.size && b[i].toInt() != 0) i++ // mime，单字节结尾
        i += 1
        if (i >= b.size) return null
        i += 1 // 图片类型
        i = skipTerminated(b, i, enc)
        if (i >= b.size) return null
        val data = b.copyOfRange(i, b.size)
        return data.takeIf { it.isNotEmpty() }
    }

    // ---------------- FLAC ----------------

    private fun parseFlac(file: File): Tags {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(4)
            var lyrics: String? = null
            var picture: ByteArray? = null
            var last = false
            val h = ByteArray(4)
            while (!last) {
                if (raf.read(h) < 4) break
                last = (h[0].toInt() and 0x80) != 0
                val type = h[0].toInt() and 0x7f
                val len = ((h[1].toInt() and 0xff) shl 16) or
                        ((h[2].toInt() and 0xff) shl 8) or (h[3].toInt() and 0xff)
                if (len <= 0 || len > MAX_FRAME) break
                val body = ByteArray(len)
                if (raf.read(body) < len) break
                when (type) {
                    4 -> if (lyrics == null) lyrics = readVorbisLyrics(body)
                    6 -> if (picture == null) picture = readFlacPicture(body)
                }
            }
            return Tags(lyrics, picture)
        }
    }

    private fun readVorbisLyrics(b: ByteArray): String? {
        var i = 0
        if (i + 4 > b.size) return null
        val vendorLen = leInt(b, i); i += 4
        if (vendorLen < 0 || i + vendorLen > b.size) return null
        i += vendorLen
        if (i + 4 > b.size) return null
        val count = leInt(b, i); i += 4
        if (count <= 0 || count > 4096) return null
        repeat(count) {
            if (i + 4 > b.size) return null
            val len = leInt(b, i); i += 4
            if (len <= 0 || i + len > b.size) return null
            val entry = String(b, i, len, Charsets.UTF_8)
            i += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "LYRIC") {
                    val v = entry.substring(eq + 1).trim()
                    if (v.isNotEmpty()) return v
                }
            }
        }
        return null
    }

    private fun readFlacPicture(b: ByteArray): ByteArray? {
        var i = 0
        if (i + 4 > b.size) return null
        i += 4 // 图片类型
        if (i + 4 > b.size) return null
        val mimeLen = beInt(b, i); i += 4
        if (mimeLen < 0 || i + mimeLen > b.size) return null
        i += mimeLen
        if (i + 4 > b.size) return null
        val descLen = beInt(b, i); i += 4
        if (descLen < 0 || i + descLen > b.size) return null
        i += descLen
        i += 16 // 宽 / 高 / 色深 / 颜色数
        if (i + 4 > b.size) return null
        val dataLen = beInt(b, i); i += 4
        if (dataLen <= 0 || i + dataLen > b.size) return null
        return b.copyOfRange(i, i + dataLen)
    }

    // ---------------- 外挂歌词 ----------------

    fun readSidecarLrc(file: File): String? {
        val base = file.absolutePath.substringBeforeLast('.')
        for (ext in arrayOf(".lrc", ".LRC", ".Lrc")) {
            val f = File(base + ext)
            if (!f.exists() || !f.canRead()) continue
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
            if (bytes.isEmpty()) continue
            // 先严格按 UTF-8 解，解不开再按 GBK
            val utf8 = runCatching {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }.getOrNull()
            if (utf8 != null && utf8.isNotBlank()) return utf8
            val gbk = runCatching {
                val cs = Charset.forName("GBK")
                cs.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }.getOrNull()
            if (!gbk.isNullOrBlank()) return gbk
        }
        return null
    }

    // ---------------- 工具 ----------------

    private fun skipTerminated(b: ByteArray, start: Int, enc: Int): Int {
        var i = start
        if (enc == 1 || enc == 2) {
            while (i + 1 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i + 2
                i += 2
            }
            return b.size
        }
        while (i < b.size) {
            if (b[i].toInt() == 0) return i + 1
            i++
        }
        return b.size
    }

    private fun decode(b: ByteArray, from: Int, to: Int, enc: Int): String? {
        if (to <= from) return null
        val charset = when (enc) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charset.forName("ISO-8859-1")
        }
        return runCatching { String(b, from, to - from, charset) }.getOrNull()
    }

    private fun syncSafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7f) shl 21) or
                ((b[off + 1].toInt() and 0x7f) shl 14) or
                ((b[off + 2].toInt() and 0x7f) shl 7) or
                (b[off + 3].toInt() and 0x7f)

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
                ((b[off + 1].toInt() and 0xff) shl 16) or
                ((b[off + 2].toInt() and 0xff) shl 8) or
                (b[off + 3].toInt() and 0xff)

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
                ((b[off + 1].toInt() and 0xff) shl 8) or
                ((b[off + 2].toInt() and 0xff) shl 16) or
                ((b[off + 3].toInt() and 0xff) shl 24)
}
