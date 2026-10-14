package com.pullup.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.data.Album
import com.pullup.player.data.TextGuard
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.components.ActionButton
import com.pullup.player.ui.components.MessageCard
import com.pullup.player.ui.components.RoundIconButton
import com.pullup.player.ui.components.SongRow
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo

/**
 * 专辑页（整页浮层）。移植自 Godot 版 `scripts/ui/AlbumScreen.gd`。
 *
 * 页面结构逐条对齐原版 `_build()`（`:94-138`）：Margin → Column → TopBar（返回圆钮 + 「专辑」小标）
 * → 专辑名 → 状态行 → Scroll（只包住曲目列表）。原版**没有封面行**，头部是纯文字的
 * 「_title + _status」，所以这里不画封面。
 *
 * 与原版的差异（有意为之）：
 * - 原版的曲目是「搜索结果顺带聚合」进来、自己不请求接口；Kotlin 版允许只拿一个 [album]
 *   进来（`overlay.album`），所以进页面时按 id 补请求一次曲目。
 * - 请求失败且调用方带了曲目时退回那份（`album.songs`）——点专辑进来时它常常是空的，
 *   但搜索聚合那条路上是有内容的，有就先用着，别让用户白屏。
 *
 * 已知坑：老接口 `album?id=` 的回包**不带 `code` 字段**，`MusicApi.getAlbumSongs` 已经
 * 绕开了这条（它不要求 code == 200），这里只管消费 `Result`，不要再去判 code。
 *
 * 底色：它是压在壳层之上的整页浮层，必须自己铺一层不透明的 `bg`，否则会透出底下的播放页。
 */
@Composable
fun AlbumScreen(album: Album) {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors

    // 在播那首的 id：列表里对应行要变强调色（原版 `_on_song_changed` → `highlight_rows`）。
    val current by app.playback.current.collectAsStateWithLifecycle()
    // 订阅黑名单：条目一变就重算可见列表（等价原版 `refresh_lists` 的重铺时机）。
    val blacklistEntries by app.blacklist.entries.collectAsStateWithLifecycle()

    var songs by remember(album.id) { mutableStateOf(album.songs) }
    var errorText by remember(album.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(album.id) {
        errorText = null
        app.api.getAlbumSongs(album.id).fold(
            onSuccess = { songs = it },
            onFailure = { e ->
                if (album.songs.isNotEmpty()) {
                    songs = album.songs
                } else {
                    songs = emptyList()
                    // 接口给的原话也要过 TextGuard：历史上后端会把 HTML/调试串塞进 message。
                    errorText = TextGuard.mask(e.message ?: "这张专辑的歌没取到")
                }
            },
        )
    }

    val visible = remember(songs, blacklistEntries) { app.blacklist.filterSongs(songs) }
    val currentId = current?.id

    // 状态行逐字照抄原版：`"%d 首" % _songs.size() + (" · " + artist if not artist.is_empty())`
    // （`AlbumScreen.gd:39`）。没有曲目时就是「0 首」——原版没有加载态文案。
    val statusText = buildString {
        append("${visible.size} 首")
        if (album.artist.isNotBlank()) append(" · ").append(album.artist)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            // 原版 Margin 上 64px / 下 40px（`AlbumScreen.gd:95-98`），÷2.5 → 26 / 16。
            .padding(start = Dims.PAD_SCREEN.dp, end = Dims.PAD_SCREEN.dp, top = 26.dp, bottom = 16.dp),
        // 原版 Column separation 20px → 8dp（`AlbumScreen.gd:103`）。
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 顶栏：返回圆钮 + 「专辑」小标（原版 heading 是 EXPAND_FILL 的 FONT_META/TEXT_DIM）----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // 原版 TopBar separation 20px → 8dp（`AlbumScreen.gd:96`）。
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 外壳只负责把浮层淡出，返回钮得自己叫一次 closeAlbum()，否则用户没有可见的退路。
            RoundIconButton(
                icon = "chevron_down",
                contentDescription = "返回",
                onClick = { shell.closeAlbum() },
            )
            Text(
                text = "专辑",
                color = c.textDim,
                fontSize = Typo.META.sp,
                modifier = Modifier.weight(1f),
            )
        }

        // ---- 专辑名（原版 `_title`：默认文案就是「专辑」，FONT_TITLE/TEXT，自动换行）----
        Text(
            text = album.name.ifBlank { "专辑" },
            color = c.text,
            fontSize = Typo.TITLE.sp,
            fontWeight = FontWeight.SemiBold,
        )

        // ---- 状态行（原版 `_status`：FONT_META/TEXT_DIM）----
        Text(text = statusText, color = c.textDim, fontSize = Typo.META.sp)

        // ---- 一键播放（用户 m02420 需求 5）：从这张专辑的第一首开始，整张当队列 ----
        // 位置与歌单详情页的「▶  播放全部」一致（`PlaylistOverlay.kt:146-151`）：钉在列表上方，
        // 不跟着滚。没有曲目时不画，避免出现点了没反应的死按钮。
        if (visible.isNotEmpty()) {
            ActionButton(
                text = "▶  播放全部",
                primary = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    app.playback.playSong(visible.first(), visible)
                    shell.openPlayer()
                },
            )
        }

        if (errorText != null) {
            MessageCard(errorText!!)
        }

        // ---- 曲目：只有列表滚，头部跟着页面钉住（原版 ScrollContainer 只包住 Songs）----
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            // 原版 Songs separation 6px → 2dp（`AlbumScreen.gd:137`）。
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(visible) { track ->
                SongRow(
                    track = track,
                    playing = currentId != null && track.id == currentId,
                    onClick = {
                        // 先起播再开播放页：原版就是这个顺序（`AlbumScreen.gd:141-145`）。
                        app.playback.playSong(track, visible)
                        shell.openPlayer()
                    },
                    // ⚠ 这是 Kotlin 版**多出来**的：原版 `AlbumScreen.gd:55-61` 的 `_make_row` 只连了
                    // `tapped`，从没连过 `SongRow.long_pressed`（`long_pressed` 是逐屏自己接线的，
                    // 见 `ArtistScreen.gd:231` / `LibraryScreen.gd:216`）。这里保留长按是为了不丢
                    // 「加入歌单」这条操作路径；若要 100% 还原原版行为，删掉这一行即可。
                    onLongClick = { shell.showSongActions(track) },
                    // 用户 m05292 项⑥：每行右侧的「+」→ 播放列表选择器 → 加进去并按当前播放模式起播。
                    onAdd = { shell.addToQueueList(track) },
                )
            }
        }
    }
}
