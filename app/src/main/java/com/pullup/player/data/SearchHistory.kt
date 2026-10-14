package com.pullup.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜索历史，最新的在最前面，最多 [MAX] 条。
 *
 * 移植自 Godot 版 `scripts/ui/SearchScreen.gd` 里的 `_history`（原存储
 * `user://search_history.cfg`）。原版这段逻辑是写在界面脚本里的，Kotlin 侧
 * 提到数据层 —— 界面只负责画，落盘口径与 `Favorites` / `History` 保持一致。
 */
class SearchHistory(context: Context) {

    companion object {
        /** 原版 `SearchScreen.gd:18` `HISTORY_MAX = 10`。 */
        const val MAX = 10
    }

    private val file = JsonFile.file(context, "search_history.json")

    private val _keywords = MutableStateFlow<List<String>>(emptyList())

    /** 界面订阅它刷新词表。 */
    val keywords: StateFlow<List<String>> = _keywords.asStateFlow()

    init {
        load()
    }

    fun list(): List<String> = _keywords.value

    fun isEmpty(): Boolean = _keywords.value.isEmpty()

    /**
     * 记一个词。重复的词会被**提到最前**而不是新增一条
     * （原版 `_remember`：先 `erase` 再 `push_front`）。
     */
    fun remember(raw: String) {
        val k = raw.trim()
        if (k.isEmpty()) return
        val next = _keywords.value.toMutableList().also {
            it.remove(k)
            it.add(0, k)
        }
        if (next.size > MAX) {
            while (next.size > MAX) next.removeAt(next.size - 1)
        }
        commit(next)
    }

    fun remove(keyword: String) {
        val next = _keywords.value.filterNot { it == keyword }
        if (next.size == _keywords.value.size) return
        commit(next)
    }

    fun clear() {
        if (_keywords.value.isEmpty()) return
        commit(emptyList())
    }

    private fun commit(next: List<String>) {
        _keywords.value = next
        save()
    }

    private fun save() {
        val arr = JSONArray()
        _keywords.value.forEach { arr.put(it) }
        JsonFile.write(file, JSONObject().put("keywords", arr))
    }

    fun load() {
        val obj = JsonFile.read(file) ?: return
        val arr = obj.optJSONArray("keywords") ?: return
        // 坏行跳过、去重后截到上限：与 Blacklist/Playlists 的读盘口径一致。
        _keywords.value = (0 until arr.length())
            .mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }
            .distinct()
            .take(MAX)
    }
}
