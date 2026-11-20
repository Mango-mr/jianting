package com.saltreplica.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 内置在线源的解析逻辑：不联网也能验 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnlineSourceTest {

    private val body = """
    {
      "result": {
        "songs": [
          {
            "id": 25706282,
            "name": "夜空中最亮的星",
            "duration": 252000,
            "artists": [{"name": "逃跑计划"}],
            "album": {"name": "世界"}
          },
          {
            "id": 1900000001,
            "name": "Test Song",
            "duration": 180000,
            "artists": [{"name": "A"}, {"name": "B"}],
            "album": {"name": "Demo"}
          },
          {
            "id": 0,
            "name": "没有 id 的脏数据",
            "artists": [{"name": "x"}]
          }
        ]
      }
    }
    """.trimIndent()

    @Test
    fun parsesKuwoPseudoJson() {
        // 酷我返回的是单引号伪 JSON，标准 JSON 解析器会直接失败
        val kuwo = """
        {'ARTISTPIC':'','HIT':'789','abslist':[{'AARTIST':'','ALBUM':'世界',
        'ARTIST':'逃跑计划','DURATION':252,'MUSICRID':'MUSIC_152906897',
        'NAME':'夜空中最亮的星','PAY':0},{'ALBUM':'',
        'ARTIST':'G.E.M.邓紫棋','DURATION':226,'MUSICRID':'MUSIC_152906898',
        'NAME':'夜空中最亮的星&nbsp;(Live)','PAY':0}]}
        """.trimIndent()

        val tracks = BuiltinSource.parseKuwo(kuwo)
        assertEquals(2, tracks.size)
        assertEquals("夜空中最亮的星", tracks[0].title)
        assertEquals("逃跑计划", tracks[0].artist)
        assertEquals("世界", tracks[0].album)
        assertEquals(252000L, tracks[0].duration)
        assertEquals("kuwo://MUSIC_152906897", tracks[0].url)
        assertTrue(BuiltinSource.isKuwoUrl(tracks[0].url))
        // html 实体要清掉
        assertEquals("夜空中最亮的星 (Live)", tracks[1].title)
    }

    @Test
    fun parsesNeteaseSearchJson() {
        val tracks = BuiltinSource.parse(body)
        assertEquals(2, tracks.size)

        val first = tracks[0]
        assertEquals("夜空中最亮的星", first.title)
        assertEquals("逃跑计划", first.artist)
        assertEquals("世界", first.album)
        assertEquals(252000L, first.duration)
        assertTrue(first.url.contains("id=25706282"))
        assertTrue(first.url.startsWith("https://"))

        assertEquals("A/B", tracks[1].artist)
    }

    @Test
    fun skipsBrokenRowsAndBlankBody() {
        assertEquals(emptyList<OnlineTrack>(), BuiltinSource.parse(""))
        assertEquals(emptyList<OnlineTrack>(), BuiltinSource.parse("not json at all"))
        assertEquals(emptyList<OnlineTrack>(), BuiltinSource.parse("""{"result":{"songs":[]}}"""))
    }

    @Test
    fun parsesRealKuwoResponse() {
        // 真实抓下来的酷我响应（app/src/test/resources/kuwo_search.txt）
        val body = javaClass.classLoader!!
            .getResourceAsStream("kuwo_search.txt")!!
            .bufferedReader(Charsets.UTF_8)
            .readText()
        val tracks = BuiltinSource.parseKuwo(body)
        assertTrue("真实响应至少能解析出 5 首", tracks.size >= 5)
        assertTrue(tracks.all { it.url.startsWith("kuwo://MUSIC_") })
        assertTrue(tracks.all { it.title.isNotBlank() && !it.title.contains("&nbsp;") })
        assertEquals("夜空中最亮的星", tracks[0].title)
        assertTrue(tracks[0].duration > 0)
    }

    @Test
    fun builtinRowIsNotDeletable() {
        val row = BuiltinSource.builtinRow()
        assertEquals(BuiltinSource.BUILTIN_ID, row.id)
        assertTrue(row.enabled)
    }

    @Test
    fun parsesKugouSearchResponse() {
        val body = """
        {
          "status": 1,
          "data": {
            "info": [
              {
                "songname": "起风了 (Live)",
                "singername": "林俊杰",
                "hash": "eaba76c97967ac133fb9de29d52d50d",
                "album_id": 123456,
                "duration": 325
              },
              {
                "songname": "",
                "singername": "脏数据"
              }
            ]
          }
        }
        """
        val tracks = BuiltinSource.parseKugou(body)
        assertEquals(1, tracks.size)
        assertEquals("起风了 (Live)", tracks[0].title)
        assertEquals("林俊杰", tracks[0].artist)
        assertEquals("kg", tracks[0].platform)
        assertEquals(325_000L, tracks[0].duration)
        assertTrue(tracks[0].url.startsWith("kugou://"))
        assertTrue(BuiltinSource.isKugouUrl(tracks[0].url))
    }
}
