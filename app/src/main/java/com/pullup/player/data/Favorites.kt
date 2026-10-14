package com.pullup.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地收藏，最新的在最前面；无账号、不上传。
 *
 * 移植自 Godot 版 scripts/Favorites.gd（原存储 user://favorites.cfg）。
 */
class Favorites(context: Context) {

    private val file = JsonFile.file(context, "favorites.json")

    private val _songs = MutableStateFlow<List<Track>>(emptyList())

    /** 界面订阅它刷新列表（等价于 Godot 版的 changed 信号）。 */
    val songs: StateFlow<List<Track>> = _songs.asStateFlow()

    init {
        load()
    }

    fun list(): List<Track> = _songs.value

    fun count(): Int = _songs.value.size

    fun isFavorite(songId: Long): Boolean = _songs.value.any { it.id == songId }

    /**
     * 收藏/取消收藏。返回 `true` 表示**最终处于已收藏**状态。
     * 空歌或 id 非正数直接拒绝（返回 false）。
     */
    fun toggle(song: Track): Boolean {
        if (song.id <= 0L) return false
        val current = _songs.value
        val existing = current.indexOfFirst { it.id == song.id }
        val next: List<Track>
        val nowFavorite: Boolean
        if (existing >= 0) {
            next = current.toMutableList().also { it.removeAt(existing) }
            nowFavorite = false
        } else {
            next = listOf(song) + current
            nowFavorite = true
        }
        commit(next)
        return nowFavorite
    }

    fun add(song: Track): Boolean {
        if (song.id <= 0L) return false
        if (isFavorite(song.id)) return true
        commit(listOf(song) + _songs.value)
        return true
    }

    fun remove(songId: Long) {
        val next = _songs.value.filterNot { it.id == songId }
        if (next.size == _songs.value.size) return
        commit(next)
    }

    fun clear() = commit(emptyList())

    private fun commit(next: List<Track>) {
        _songs.value = next
        save()
    }

    private fun save() {
        val obj = JSONObject().put("songs", JSONArray().also { arr -> _songs.value.forEach { arr.put(it.toJson()) } })
        JsonFile.write(file, obj)
    }

    fun load() {
        val obj = JsonFile.read(file) ?: return
        val arr = obj.optJSONArray("songs") ?: return
        _songs.value = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { trackFromJson(it) }
        }
    }
}
