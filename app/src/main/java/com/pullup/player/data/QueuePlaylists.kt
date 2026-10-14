package com.pullup.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放列表（**不是**歌单）：一个可保存的播放队列。
 *
 * 与 [Playlists] 的分工：歌单是「收藏夹」——加进去只是存着，不改变当前播放；
 * 播放列表是「待播队列」——歌曲行右侧「+」弹它，选一个列表把歌放进去，
 * 然后**立刻按当前播放模式起播**（沿用随机/单曲/列表，不重置）。
 *
 * Godot 原版没有这个概念（全 `scripts/` grep 队列语义只有
 * `PlayerManager.gd:189-194` 的 `play(song, list)`，即内存里的 play queue，
 * 不持久化、没有 UI），所以这是用户 m05292 项⑤⑥ 要的全新功能，
 * 落盘口径照本工程既有的 [SearchHistory] 先例（JSON + StateFlow + 上限 + 去重置顶）。
 *
 * 每条列表存 id / 名字 / 歌曲快照。歌曲按原样序列化（含负 id 的本地曲目），
 * 因为播放列表的第一用途就是「接着放」——本地曲目同样该能排进去。
 */
class QueuePlaylists(context: Context) {

    companion object {
        /** 落盘文件名（与 playlists.json / search_history.json 同目录）。 */
        const val FILE_NAME = "queue_playlists.json"

        /** 列表条数上限：够用又不至于让选择器变成长滚动。 */
        const val MAX_LISTS = 20

        /** 单列表歌曲数上限，与 [History] 的 48 首一档，防无界增长。 */
        const val MAX_SONGS = 200

        /** 新列表默认名（原版「未命名歌单」同口径，用户可改）。 */
        const val DEFAULT_NAME = "未命名播放列表"
    }

    private val file = JsonFile.file(context, FILE_NAME)

    private val _lists = MutableStateFlow<List<QueuePlaylist>>(emptyList())

    /** 全部播放列表，界面订阅它刷新选择器。 */
    val lists: StateFlow<List<QueuePlaylist>> = _lists.asStateFlow()

    /** 最近一次操作的结果文案（给界面弹 toast 用，空串表示没话说）。 */
    var lastMessage: String = ""
        private set

    init {
        load()
    }

    // -----------------------------------------------------------------------
    // 读
    // -----------------------------------------------------------------------

    fun all(): List<QueuePlaylist> = _lists.value

    fun find(id: String): QueuePlaylist? = _lists.value.firstOrNull { it.id == id }

    fun isEmpty(): Boolean = _lists.value.isEmpty()

    // -----------------------------------------------------------------------
    // 写
    // -----------------------------------------------------------------------

    /**
     * 把一首歌加进某个列表；列表不存在时按 [name] 新建一个（[name] 为空用默认名）。
     * 返回目标列表，调用方可直接拿 `target.songs` 当播放队列。
     *
     * 已存在的歌**不重复加**（按 id 判重；本地曲目 id 为负同样可比）。
     */
    fun add(listId: String, song: Track, name: String = ""): QueuePlaylist {
        val target = _lists.value.firstOrNull { it.id == listId }
            ?: create(name.ifBlank { DEFAULT_NAME })
        if (target.songs.none { it.id == song.id }) {
            val songs = (target.songs + song).takeLast(MAX_SONGS)
            update(target.id) { it.copy(songs = songs) }
        }
        return find(target.id) ?: target
    }

    /** 新建列表，同名的会加数字后缀（「列表」「列表 2」「列表 3」）。 */
    fun create(rawName: String): QueuePlaylist {
        val name = uniqueName(rawName)
        val pl = QueuePlaylist(
            id = newId(),
            name = name,
            songs = emptyList(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        _lists.value = _lists.value + pl
        save()
        return pl
    }

    /** 改名（空名保持原名，不改盘）。 */
    fun rename(id: String, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        update(id) { it.copy(name = name) }
    }

    /** 从列表里移除一首歌。 */
    fun removeSong(id: String, songId: Long) {
        val target = find(id) ?: return
        update(id) { it.copy(songs = it.songs.filterNot { s -> s.id == songId }) }
    }

    /** 删整条列表。 */
    fun remove(id: String) {
        if (_lists.value.none { it.id == id }) return
        _lists.value = _lists.value.filterNot { it.id == id }
        save()
    }

    /** 置顶一条列表（选择器里最近用过的排前面）。 */
    fun pinToTop(id: String) {
        val target = find(id) ?: return
        if (_lists.value.firstOrNull()?.id == id) return
        _lists.value = listOf(target) + _lists.value.filterNot { it.id == id }
        save()
    }

    /** 只对一条做改动并落盘；找不到就什么也不做。 */
    private fun update(id: String, block: (QueuePlaylist) -> QueuePlaylist) {
        var changed = false
        _lists.value = _lists.value.map { pl ->
            if (pl.id != id) pl else {
                changed = true
                block(pl).copy(updatedAt = System.currentTimeMillis())
            }
        }
        if (changed) save()
    }

    private fun uniqueName(raw: String): String {
        val base = raw.trim().ifEmpty { DEFAULT_NAME }
        if (_lists.value.none { it.name == base }) return base
        var i = 2
        while (_lists.value.any { it.name == "$base $i" }) i++
        return "$base $i"
    }

    private fun newId(): String = "qp_" + System.currentTimeMillis().toString(36) + "_" +
        (1000 + (System.nanoTime() % 9000)).toString(36)

    // -----------------------------------------------------------------------
    // 落盘
    // -----------------------------------------------------------------------

    private fun save() {
        val arr = JSONArray()
        _lists.value.forEach { arr.put(it.toJson()) }
        JsonFile.write(file, JSONObject().put("lists", arr))
    }

    private fun load() {
        val obj = JsonFile.read(file) ?: return
        val arr = obj.optJSONArray("lists") ?: return
        // 坏行跳过、按 id 去重、截到上限：与 SearchHistory/Blacklist 的读盘口径一致。
        _lists.value = (0 until arr.length())
            .mapNotNull { i -> arr.optJSONObject(i)?.let { QueuePlaylist.fromJson(it) } }
            .distinctBy { it.id }
            .take(MAX_LISTS)
    }
}

/** 一条播放列表。字段名保持蛇形，与 playlists.json 对照。 */
data class QueuePlaylist(
    val id: String = "",
    val name: String = "未命名播放列表",
    val songs: List<Track> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 选择器副标题：`"N 首"`。 */
    val metaText: String get() = "${songs.size} 首"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("created_ms", createdAt)
        put("updated_ms", updatedAt)
        put("songs", JSONArray().also { arr -> songs.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): QueuePlaylist = QueuePlaylist(
            id = o.optString("id", ""),
            name = o.optString("name", "未命名播放列表"),
            songs = o.optJSONArray("songs").let { arr ->
                if (arr == null) emptyList()
                else (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { trackFromJson(it) }
                }
            },
            createdAt = o.optLong("created_ms", 0L),
            updatedAt = o.optLong("updated_ms", 0L),
        )
    }
}
