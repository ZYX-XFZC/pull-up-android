package com.pullup.player.data

import org.json.JSONObject

/** 歌单种类。`favorites` 只是「我的收藏」的投影，永不落盘。 */
object PlaylistKind {
    const val DAILY = "daily"
    const val USER = "user"
    const val FAVORITES = "favorites"
}

/**
 * 自建/日推歌单。
 *
 * 移植自 Godot 版 Playlists.gd 的歌单字典，字段名保持蛇形以便与旧档对照。
 */
data class LocalPlaylist(
    val id: String = "",
    val kind: String = PlaylistKind.USER,
    val name: String = "未命名歌单",
    val cover: String = "",
    val songs: List<Track> = emptyList(),
    val trackCount: Int = 0,
    val isPrivate: Boolean = false,
    /** sha256 十六进制；明文永不落盘。 */
    val password: String = "",
    val creator: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** 排序期临时字段（TasteModel.rankPlaylists 写入），不落盘。 */
    val tasteScore: Float = 0f,
    val tasteReason: String = "",
) {
    val desc: String get() = ""

    /** 卡片副标题：`"%d 首"` (+ `" · 私密"`)。 */
    val metaText: String
        get() = buildString {
            append(trackCount.coerceAtLeast(songs.size)).append(" 首")
            if (isPrivate) append(" · 私密")
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("name", name)
        put("cover", cover)
        put("track_count", trackCount)
        put("private", isPrivate)
        put("password", password)
        put("creator", creator)
        put("created_ms", createdAt)
        put("updated_ms", updatedAt)
        put("songs", org.json.JSONArray().also { arr -> songs.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): LocalPlaylist = LocalPlaylist(
            id = o.optString("id", ""),
            kind = o.optString("kind", PlaylistKind.USER),
            name = o.optString("name", "未命名歌单"),
            cover = o.optString("cover", ""),
            songs = o.optJSONArray("songs").let { arr ->
                if (arr == null) emptyList()
                else (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { trackFromJson(it) } }
            },
            trackCount = o.optInt("track_count", 0),
            isPrivate = o.optBoolean("private", false),
            password = o.optString("password", ""),
            creator = o.optString("creator", ""),
            createdAt = o.optLong("created_ms", 0L),
            updatedAt = o.optLong("updated_ms", 0L),
        )
    }
}
