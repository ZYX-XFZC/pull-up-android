package com.pullup.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 一条播放记录：歌曲 + 播放时刻（Unix 秒）。 */
data class HistoryEntry(val song: Track, val playedAt: Long)

/**
 * 最近播放，只保留 48 小时、最多 100 条。
 *
 * 移植自 Godot 版 scripts/History.gd（原存储 user://history.cfg）。
 * 记录时机是「音频真的开始播之后」——下载失败或格式不支持永远不会进历史。
 */
class History(context: Context) {

    companion object {
        /** 48 小时。 */
        const val RETENTION_SECONDS = 48L * 3600L

        /** 硬上限。 */
        const val MAX_ENTRIES = 100
    }

    private val file = JsonFile.file(context, "history.json")

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init {
        load()
        prune()
    }

    fun list(): List<Track> {
        // 原版 list() 自己会 prune（History.gd:55）：过期条目在读的那一刻就消失，
        // 调用方不需要先手动清一次。
        prune()
        return _entries.value.map { it.song }
    }

    fun count(): Int {
        prune()
        return _entries.value.size
    }

    /** 记一次播放。成功播放后调用。 */
    fun record(song: Track) = recordAt(song, System.currentTimeMillis() / 1000L)

    /** 指定时刻落一条（测试/补录入口）。 */
    fun recordAt(song: Track, unixSeconds: Long) {
        // 原版只收 id > 0 的歌（History.gd:36）——本地文件用负 id，不进历史。
        if (song.id <= 0L) return
        // 先摘掉同 id 的旧记录 —— 移向队首，永不重复。
        val next = _entries.value.filterNot { it.song.id == song.id }.toMutableList()
        next.add(0, HistoryEntry(song, unixSeconds))
        if (next.size > MAX_ENTRIES) {
            while (next.size > MAX_ENTRIES) next.removeAt(next.size - 1)
        }
        _entries.value = next
        save()
        // 补录的旧时间戳要立刻被 48 小时窗口裁掉（Godot 在 push_front 之后紧跟 _prune()）。
        prune()
    }

    fun clear() {
        _entries.value = emptyList()
        save()
    }

    /** 丢掉超过 48 小时的。读和写都会跑一次。 */
    fun prune() {
        val cutoff = System.currentTimeMillis() / 1000L - RETENTION_SECONDS
        val next = _entries.value.filter { it.playedAt >= cutoff }
        if (next.size != _entries.value.size) {
            _entries.value = next
            save()
        }
    }

    private fun save() {
        val obj = JSONObject().put(
            "entries",
            JSONArray().also { arr ->
                _entries.value.forEach { e ->
                    arr.put(
                        JSONObject()
                            .put("song", e.song.toJson())
                            .put("played_at", e.playedAt),
                    )
                }
            },
        )
        JsonFile.write(file, obj)
    }

    fun load() {
        val obj = JsonFile.read(file) ?: return
        val arr = obj.optJSONArray("entries") ?: return
        _entries.value = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val song = o.optJSONObject("song") ?: return@mapNotNull null
            HistoryEntry(trackFromJson(song), o.optLong("played_at", 0L))
        }
    }
}
