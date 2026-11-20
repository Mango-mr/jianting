package com.saltreplica.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户自建的在线音源，两种：
 * · [KIND_API] 返回 JSON 的搜索接口
 * · [KIND_LX]  lx-music 的 JS 脚本源（只负责把某首歌换成播放地址）
 */
data class MusicSource(
    val id: Long,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val kind: String = MusicSourceStore.KIND_API,
    /** 脚本源的平台/音质摘要，用于列表副标题 */
    val summary: String = ""
) {
    val isLx: Boolean get() = kind == MusicSourceStore.KIND_LX
}

/** 音源配置持久化（SharedPreferences 存 JSON，避免新增数据库迁移） */
object MusicSourceStore {

    const val KIND_API = "api"
    const val KIND_LX = "lx"

    private lateinit var sp: SharedPreferences
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        sp = context.applicationContext.getSharedPreferences("music_sources", Context.MODE_PRIVATE)
        initialized = true
    }

    fun all(): List<MusicSource> {
        if (!initialized) return emptyList()
        val raw = sp.getString("list", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                MusicSource(
                    id = item.optLong("id"),
                    name = item.optString("name", "未命名音源"),
                    url = item.optString("url"),
                    enabled = item.optBoolean("enabled", true),
                    kind = item.optString("kind", KIND_API),
                    summary = item.optString("summary", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(list: List<MusicSource>) {
        if (!initialized) return
        val array = JSONArray()
        list.forEach { source ->
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("url", source.url)
                    .put("enabled", source.enabled)
                    .put("kind", source.kind)
                    .put("summary", source.summary)
            )
        }
        sp.edit().putString("list", array.toString()).apply()
    }

    fun add(name: String, url: String, kind: String = KIND_API): Long {
        val list = all().toMutableList()
        val nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1L
        list.add(MusicSource(nextId, name, url, true, kind))
        save(list)
        return nextId
    }

    fun updateSummary(id: Long, summary: String, name: String? = null) {
        save(
            all().map {
                if (it.id != id) it
                else it.copy(summary = summary, name = name?.takeIf(String::isNotBlank) ?: it.name)
            }
        )
    }

    fun remove(id: Long) = save(all().filterNot { it.id == id })

    fun toggle(id: Long) = save(all().map { if (it.id == id) it.copy(enabled = !it.enabled) else it })

    fun enabled(): List<MusicSource> = all().filter { it.enabled }
}
