package com.pullup.player.data

import org.json.JSONObject

/**
 * 全项目统一的歌曲形状，对应 Godot 版 APIHandler.normalize_song() 的产物。
 *
 * 两个上游接口的回包形状不同（`artists`/`album`/`duration` 与 `ar`/`al`/`dt`），
 * 归一化后 UI 只认这一种。
 */
data class Track(
    val id: Long = 0L,
    val name: String = "未知歌曲",
    val artist: String = "未知歌手",
    /** 歌手 id：只有 /song/detail、/playlist/detail 这类接口会给，搜索接口只给名字。歌手页靠它跳转。 */
    val artistId: Long = 0L,
    val album: String = "",
    val cover: String = "",
    /** 毫秒。 */
    val duration: Int = 0,
    /** MeTing 回包里直接带的播放地址，省掉一次解析。 */
    val stream: String = "",
    /** MeTing 回包带的歌词地址。 */
    val lrc: String = "",
    val source: String = "",
    /** 本地导入的文件（负 id，落在应用私有目录）。 */
    val local: Boolean = false,
    /** 本地文件的绝对路径；远程歌曲为空。 */
    val path: String = "",
    /** TasteModel.scoreSong() 就地写入的三项：语种 / 得分 / 人话理由。 */
    val tasteLanguage: String = "",
    val tasteScore: Float = 0f,
    val tasteReason: String = "",
) {
    val durationStr: String get() = formatDuration(duration)

    /** 列表复用/比较用：同名同歌手同专辑视为同一首。 */
    val identity: String get() = "$id@$name@$artist"
}

/** 毫秒 → "m:ss"。等价于 APIHandler.format_duration()。 */
fun formatDuration(ms: Int): String {
    val total = (maxOf(ms, 0) / 1000)
    return "%d:%02d".format(total / 60, total % 60)
}

/** 歌单/专辑卡片共用的形状（远程数据）。 */
data class Playlist(
    val id: Long = 0L,
    val name: String = "未命名歌单",
    val cover: String = "",
    val trackCount: Int = 0,
    val playCount: Long = 0L,
    val creator: String = "",
    val description: String = "",
    val songs: List<Track> = emptyList(),
)

/** 歌手专辑卡片（老接口 `hotAlbums[]`）。 */
data class Album(
    val id: Long = 0L,
    val name: String = "",
    val cover: String = "",
    val trackCount: Int = 0,
    val artist: String = "",
    val songs: List<Track> = emptyList(),
)

/** 解析后的 LRC 行。 */
data class LyricLine(val time: Float, val text: String)

/**
 * 播放结果：成功时 [url] 有效，失败时 [error] 是给用户看的中文原因。
 *
 * [Ok.served] 是**服务端实际下发的音质档位**（网易云的 `level` 字段原值），不是我们请求的那一档 ——
 * 这正是「切音质」能被验证的关键：请求 `lossless` 时服务端只会给 `exhigh`，
 * 界面据此如实显示，而不是把用户选的档位当成已生效。
 */
sealed interface UrlResult {
    data class Ok(val url: String, val served: String = "") : UrlResult
    data class Fail(val error: String) : UrlResult
}

/** 统一的加载状态，供 UI 三态渲染（加载中 / 有数据 / 空或错误）。 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ok<T>(val data: T) : LoadState<T>
    data class Err(val message: String) : LoadState<Nothing>
}

// ---------------------------------------------------------------------------
// JSON 往返：本地库（收藏 / 历史 / 歌单 / 会话）全部按这个形状落盘。
// ---------------------------------------------------------------------------

/** 键名沿用 Godot 版的蛇形命名，方便日后从旧 ConfigFile 迁移时对得上。 */
fun Track.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("artist", artist)
    put("artist_id", artistId)
    put("album", album)
    put("cover", cover)
    put("duration", duration)
    if (stream.isNotEmpty()) put("stream", stream)
    if (lrc.isNotEmpty()) put("lrc", lrc)
    if (source.isNotEmpty()) put("source", source)
    if (local) put("local", true)
    if (path.isNotEmpty()) put("path", path)
}

fun trackFromJson(o: JSONObject): Track = Track(
    id = o.optLong("id", 0L),
    name = o.optString("name", "未知歌曲"),
    artist = o.optString("artist", "未知歌手"),
    artistId = o.optLong("artist_id", 0L),
    album = o.optString("album", ""),
    cover = o.optString("cover", ""),
    duration = o.optInt("duration", 0),
    stream = o.optString("stream", ""),
    lrc = o.optString("lrc", ""),
    source = o.optString("source", ""),
    local = o.optBoolean("local", false),
    path = o.optString("path", ""),
)
