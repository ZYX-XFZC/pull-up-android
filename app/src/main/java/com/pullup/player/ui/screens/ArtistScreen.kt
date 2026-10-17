package com.pullup.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.data.Album
import com.pullup.player.data.TextGuard
import com.pullup.player.data.Track
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.components.ActionButton
import com.pullup.player.ui.components.DetailQueueBar
import com.pullup.player.ui.components.MessageCard
import com.pullup.player.ui.components.PlaylistTile
import com.pullup.player.ui.components.RoundIconButton
import com.pullup.player.ui.components.SectionHeader
import com.pullup.player.ui.components.SongRow
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo

/**
 * 歌手页（整页浮层）。移植自 Godot 版 `scripts/ui/ArtistScreen.gd`。
 *
 * 入口传入的是一首 [Track]（播放页点歌手名 / 长按歌曲）。**搜索接口只给歌手名字、不给 id**
 * （`ArtistScreen.gd` 头部注释），所以 `song.artistId <= 0` 时原版会先拿 `/song/detail` 反查一次
 * `ar[0].id`，再请求 `/artist/top/song`（`ArtistScreen.gd:55-73, :186-205`）。
 * Kotlin 版这一段走 `MusicApi.enrich()`——它就是 `/song/detail?ids=`，并且**只在缺 id 时回填
 * `artistId`**（`data/MusicApi.kt:123-139`），语义与原版的反查一致，数据层不需要改动。
 *
 * 底色：它是压在壳层之上的整页浮层，必须自己铺一层不透明的 `bg`，否则会透出底下的播放页。
 */
@Composable
fun ArtistScreen(song: Track) {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors

    val current by app.playback.current.collectAsStateWithLifecycle()
    val blacklistEntries by app.blacklist.entries.collectAsStateWithLifecycle()

    // 原版 `_artist_id`：进来先用歌曲自带的 id；为 0 才走反查（`ArtistScreen.gd:57`）。
    var artistId by remember(song.id) { mutableStateOf(song.artistId) }
    // 标题先用传进来的歌手名顶着，接口回来若解析出更准的名字再覆盖（原版 `_title.text = artist_name`）。
    var title by remember(song.id, song.artist) {
        mutableStateOf(song.artist.ifBlank { "歌手" })
    }
    var songs by remember(song.id) { mutableStateOf<List<Track>>(emptyList()) }
    var albums by remember(song.id) { mutableStateOf<List<Album>>(emptyList()) }
    var loading by remember(song.id) { mutableStateOf(song.artistId > 0) }
    var resolving by remember(song.id) { mutableStateOf(false) }
    var errorText by remember(song.id) { mutableStateOf<String?>(null) }

    // ---- 反查 artist_id（原版 `open_for_song` 的 else 分支 + `_on_details_received`）----
    LaunchedEffect(song.id) {
        if (artistId > 0) return@LaunchedEffect
        if (song.id <= 0L) {
            // 逐字照抄原版：连歌都没有 id 时的那句（`ArtistScreen.gd:69`）。
            loading = false
            errorText = "拿不到这位歌手的信息"
            return@LaunchedEffect
        }
        resolving = true
        errorText = null
        // `enrich()` 内部已把网络失败吞掉（返回原列表），所以这里再兜一层解析异常。
        val resolved = runCatching { app.api.enrich(listOf(song)) }.getOrNull().orEmpty()
            .firstOrNull { it.id == song.id }
        resolving = false
        val resolvedId = resolved?.artistId ?: 0L
        if (resolvedId > 0L) {
            val resolvedName = resolved?.artist.orEmpty()
            if (resolvedName.isNotBlank()) title = resolvedName
            loading = true
            artistId = resolvedId // 改 key 让下面的正片请求接力
        } else {
            // 原版这里分两句：请求失败是「没找到这位歌手」（`:192`），反查到歌却没有 artist_id 是
            // 「这位歌手没有可跳转的主页」（`:205`）。`enrich()` 把「请求失败」和「回包没这首」都
            // 折成「原样返回」，两种情形在 Kotlin 侧不可区分，取更常见的网络失败口径。
            loading = false
            errorText = "没找到这位歌手"
        }
    }

    // ---- 正片：歌手热门歌曲 + 专辑（原版 `_load()` / `_load_albums()`）----
    LaunchedEffect(song.id, artistId) {
        if (artistId <= 0L) return@LaunchedEffect
        loading = true
        errorText = null

        app.api.getArtistSongs(artistId, 50).fold(
            onSuccess = { (resolvedName, list) ->
                if (resolvedName.isNotBlank()) title = resolvedName
                songs = list
            },
            onFailure = { e ->
                songs = emptyList()
                errorText = TextGuard.mask(e.message ?: "没找到这位歌手")
            },
        )
        // 专辑区失败就整块不出现（原版 `_album_box.visible` 只在有专辑时才亮，不占位）。
        app.api.getArtistAlbums(artistId, 30).fold(
            onSuccess = { albums = it },
            onFailure = { albums = emptyList() },
        )
        loading = false
    }

    val visibleSongs = remember(songs, blacklistEntries) { app.blacklist.filterSongs(songs) }
    val currentId = current?.id

    // 状态行：反查中「正在查找歌手…」（`ArtistScreen.gd:72`）→ 加载中「正在加载…」（`:181`）
    // → 拿到后「♪ N 首」（`:219`）；出错时显示错误本身（`_show_message` 同时铺状态行与消息卡）。
    val statusText = when {
        errorText != null -> errorText!!
        resolving -> "正在查找歌手…"
        loading -> "正在加载…"
        else -> "♪ ${visibleSongs.size} 首"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            // 原版 Margin 上 64px / 下 40px（`ArtistScreen.gd:88-95`），÷2.5 → 25.6 / 16。
            .padding(start = Dims.PAD_SCREEN.dp, end = Dims.PAD_SCREEN.dp, top = 26.dp, bottom = 16.dp),
        // 原版 Column separation 24px → 9.6dp（`ArtistScreen.gd:104`）。
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- 顶栏：返回圆钮 + 歌手名（原版 TopBar 的 _title 就是 FONT_TITLE 的 TEXT）----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // 原版 TopBar separation 20px → 8dp（`ArtistScreen.gd:118`）。
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 外壳只负责把浮层淡出，返回钮得自己叫一次 closeArtist()，否则用户没有可见的退路。
            RoundIconButton(
                icon = "chevron_down",
                contentDescription = "返回",
                onClick = { shell.closeArtist() },
            )
            Text(
                text = title,
                color = c.text,
                fontSize = Typo.TITLE.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        // 原版这个节标题写在滚动区之外（`ArtistScreen.gd:128`），跟着页面钉住。
        SectionHeader("热门歌曲", "来自歌手")
        Text(text = statusText, color = c.textDim, fontSize = Typo.META.sp)

        // ---- 一键播放（用户 m02420 需求 4）：从这位歌手的第一首热门歌开始 ----
        // 与专辑页同款，钉在列表上方不跟着滚。加载中/空列表时不画。
        if (visibleSongs.isNotEmpty()) {
            ActionButton(
                text = "▶  播放全部",
                primary = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    app.playback.playSong(visibleSongs.first(), visibleSongs)
                    shell.openPlayer()
                },
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            // 原版 Songs separation 6px → 2dp（`ArtistScreen.gd:171`）。
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // ---- 专辑区：只在这位歌手确实有专辑时才出现（否则整块不占空间）----
            if (albums.isNotEmpty()) {
                item {
                    Column(
                        // 原版 Body separation 24px → 10dp：专辑块与歌曲列表之间的间距
                        // （`ArtistScreen.gd:158-162`）。
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        // 原版 AlbumSection separation 12px → 5dp（`ArtistScreen.gd:160`）。
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        SectionHeader("专辑", "")
                        // 逐字照抄原版：`"%d 张 · 点一张看曲目" % albums.size()`。
                        Text(
                            text = "${albums.size} 张 · 点一张看曲目",
                            color = c.textDim,
                            fontSize = Typo.META.sp,
                        )
                        LazyRow(
                            // 原版 Albums separation 6px → 2dp（`ArtistScreen.gd:163`）。
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        ) {
                            items(albums) { album ->
                                PlaylistTile(
                                    name = album.name,
                                    cover = album.cover,
                                    // 原版 `make_playlist_card` 的 meta = "%d 首" + " · " + creator，
                                    // creator 缺省回落到页标题（歌手专辑接口本身不带 artist）。
                                    meta = buildString {
                                        append("${album.trackCount} 首")
                                        val creator = album.artist.ifBlank { title }
                                        if (creator.isNotBlank()) append(" · ").append(creator)
                                    },
                                    // 只带过去专辑本身，曲目由专辑页按 id 自己补请求（`album.songs` 是空的）。
                                    onClick = { shell.openAlbum(album) },
                                )
                            }
                        }
                    }
                }
            }

            items(visibleSongs) { track ->
                SongRow(
                    track = track,
                    playing = currentId != null && track.id == currentId,
                    onClick = {
                        // 先起播再开播放页：原版就是这个顺序（`ArtistScreen.gd:254-258`）。
                        app.playback.playSong(track, visibleSongs)
                        shell.openPlayer()
                    },
                    onLongClick = { shell.showSongActions(track) },
                    // 用户 m05292 项⑥：每行右侧的「+」→ 播放列表选择器 → 加进去并按当前播放模式起播。
                    onAdd = { shell.addToQueue(track) },
                )
            }

            if (errorText != null) {
                item {
                    Spacer(Modifier.height(6.dp))
                    MessageCard(errorText!!)
                }
            }
        }

        // ---- 贴底入口条：播放队列（原版 `UIFactory.gd:438-480 _build_detail_bar()`）----
        // 原版三个详情页（歌单/专辑/歌手）各挂一条，塞进迷你条让位后露出的那 200px 空白里。
        // Kotlin 侧放在列表下方，功能等价：高 48dp、圆角矩形、点进队列页。
        DetailQueueBar(onClick = { shell.openQueue() })
    }
}
