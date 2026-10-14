package com.pullup.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 黑名单条目的四种维度。 */
object BlacklistKind {
    const val SONG = "song"
    const val ARTIST = "artist"
    const val TAG = "tag"
    const val LANGUAGE = "language"

    val ALL = listOf(SONG, ARTIST, TAG, LANGUAGE)

    val NAMES = mapOf(SONG to "歌曲", ARTIST to "作者", TAG to "曲风", LANGUAGE to "语种")

    val LANGUAGE_NAMES = mapOf("zh" to "华语", "en" to "欧美", "ja" to "日语", "ko" to "韩语")

    /** 曲风标签：显示名 → 命中的关键词。 */
    val TAG_KEYWORDS: Map<String, List<String>> = linkedMapOf(
        "摇滚" to listOf("摇滚", "rock"),
        "说唱" to listOf("说唱", "rap", "hiphop", "hip-hop", "嘻哈"),
        "电音" to listOf("电音", "edm", "electronic", "电子"),
        "舞曲" to listOf("舞曲", "dance", "disco", "迪斯科", "remix"),
        "民谣" to listOf("民谣", "folk"),
        "抒情" to listOf("抒情", "ballad", "情歌"),
        "纯音乐" to listOf("纯音乐", "instrumental", "伴奏", "钢琴曲"),
    )
}

/** 黑名单条目。 */
data class BlacklistEntry(val kind: String, val value: String)

/**
 * 屏蔽一首歌 / 一个作者 / 一种曲风 / 一个语种，让它从所有列表（因而也从所有播放队列）里消失。
 *
 * 移植自 Godot 版 scripts/Blacklist.gd（原存储 user://blacklist.cfg）。
 */
class Blacklist(context: Context) {

    private val file = JsonFile.file(context, "blacklist.json")

    private val _entries = MutableStateFlow<List<BlacklistEntry>>(emptyList())
    val entries: StateFlow<List<BlacklistEntry>> = _entries.asStateFlow()

    init {
        load()
    }

    fun list(): List<BlacklistEntry> = _entries.value

    fun isEmpty(): Boolean = _entries.value.isEmpty()

    fun kindName(kind: String): String = BlacklistKind.NAMES[kind] ?: kind

    fun hasPair(kind: String, value: String): Boolean =
        _entries.value.any { it.kind == kind && it.value == value }

    /**
     * 这首歌被屏蔽的原因（人话），空数组表示没被屏蔽。
     * 一首歌可能同时命中多条。
     */
    fun reasons(song: Track): List<String> {
        val out = mutableListOf<String>()
        _entries.value.forEach { e ->
            when (e.kind) {
                BlacklistKind.SONG -> {
                    val hit = if (song.id > 0L) e.value == "id:${song.id}" else e.value == song.name
                    if (hit) out.add("歌曲「${song.name}」")
                }
                BlacklistKind.ARTIST -> {
                    val key = artistKey(song)
                    if (key.isNotEmpty() && e.value.equals(key, ignoreCase = true)) {
                        out.add("作者「${song.artist}」")
                    }
                }
                BlacklistKind.LANGUAGE -> {
                    if (e.value == TasteModel.detectLanguage(song.name, song.artist)) {
                        out.add("语种「${BlacklistKind.LANGUAGE_NAMES[e.value] ?: e.value}」")
                    }
                }
                BlacklistKind.TAG -> {
                    val kws = BlacklistKind.TAG_KEYWORDS[e.value] ?: return@forEach
                    val hay = "${song.name} ${song.artist}".lowercase()
                    if (kws.any { hay.contains(it) }) out.add("曲风「${e.value}」")
                }
            }
        }
        return out
    }

    fun isBlocked(song: Track): Boolean = reasons(song).isNotEmpty()

    /**
     * 过滤掉被屏蔽的歌。
     *
     * **渲染前调用**：屏幕上的列表同时就是播放队列，所以这样队列也一定是干净的。
     */
    fun filterSongs(songs: List<Track>): List<Track> {
        if (_entries.value.isEmpty()) return songs
        return songs.filter { !isBlocked(it) }
    }

    /** 给操作面板列出这首歌可切换的屏蔽项。 */
    fun optionsFor(song: Track): List<BlacklistOption> {
        val out = mutableListOf<BlacklistOption>()
        val songValue = if (song.id > 0L) "id:${song.id}" else song.name
        out.add(BlacklistOption(BlacklistKind.SONG, songValue, "歌曲", hasPair(BlacklistKind.SONG, songValue)))

        val artist = artistKey(song)
        if (artist.isNotEmpty()) {
            out.add(
                BlacklistOption(BlacklistKind.ARTIST, artist, "作者「${song.artist}」", hasPair(BlacklistKind.ARTIST, artist)),
            )
        }

        val lang = TasteModel.detectLanguage(song.name, song.artist)
        out.add(
            BlacklistOption(
                BlacklistKind.LANGUAGE,
                lang,
                "语种「${BlacklistKind.LANGUAGE_NAMES[lang] ?: lang}」",
                hasPair(BlacklistKind.LANGUAGE, lang),
            ),
        )

        val hay = "${song.name} ${song.artist}".lowercase()
        BlacklistKind.TAG_KEYWORDS.forEach { (display, kws) ->
            if (kws.any { hay.contains(it) }) {
                out.add(BlacklistOption(BlacklistKind.TAG, display, "曲风「$display」", hasPair(BlacklistKind.TAG, display)))
            }
        }
        return out
    }

    fun add(kind: String, value: String) {
        val v = value.trim()
        if (v.isEmpty()) return
        if (kind !in BlacklistKind.ALL) return
        if (hasPair(kind, v)) return
        commit(_entries.value + BlacklistEntry(kind, v))
    }

    fun remove(kind: String, value: String) {
        val next = _entries.value.filterNot { it.kind == kind && it.value == value }
        if (next.size == _entries.value.size) return
        commit(next)
    }

    /** 切换屏蔽状态。返回 `true` 表示现在处于已屏蔽。 */
    fun toggle(kind: String, value: String): Boolean {
        val blocked = hasPair(kind, value)
        if (blocked) remove(kind, value) else add(kind, value)
        return !blocked
    }

    fun clear() = commit(emptyList())

    /** 作者键：优先用名字，没有名字就退回 artist id。 */
    private fun artistKey(song: Track): String =
        song.artist.trim().takeIf { it.isNotEmpty() && it != "未知歌手" } ?: song.artistId.takeIf { it > 0L }?.toString().orEmpty()

    private fun commit(next: List<BlacklistEntry>) {
        _entries.value = next
        save()
    }

    private fun save() {
        val obj = JSONObject().put(
            "entries",
            JSONArray().also { arr ->
                _entries.value.forEach { e ->
                    arr.put(JSONObject().put("kind", e.kind).put("value", e.value))
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
            val kind = o.optString("kind", "")
            val value = o.optString("value", "").trim()
            // 坏行直接跳过：非四种维度、空值、重复项。
            if (kind !in BlacklistKind.ALL || value.isEmpty()) return@mapNotNull null
            BlacklistEntry(kind, value)
        }.distinctBy { it.kind to it.value }
    }
}

/** 操作面板里的一行屏蔽开关。 */
data class BlacklistOption(
    val kind: String,
    val value: String,
    val label: String,
    val blocked: Boolean,
)
