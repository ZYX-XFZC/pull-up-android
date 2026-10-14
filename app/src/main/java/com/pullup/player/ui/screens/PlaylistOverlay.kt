package com.pullup.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.data.Playlists
import com.pullup.player.data.TextGuard
import com.pullup.player.data.Track
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.components.ActionButton
import com.pullup.player.ui.components.MessageCard
import com.pullup.player.ui.components.RoundIconButton
import com.pullup.player.ui.components.SongRow
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo

// ---------------------------------------------------------------------------
// 与 `SubScreen.gd` / `PlaylistScreen.gd` 对齐的几何（画布 1080px = 432dp，
// 即 1dp = 2.5px，原值见 `scripts/ui/AppTheme.gd:64`；下列数值都是 px ÷ 2.5 的**精确值**，
// 不要再除一次 2.5，也不要取整成整数 dp）。
// ---------------------------------------------------------------------------

/** `SubScreen.gd:134` `Margin.margin_top = 64` → 25.6dp。 */
private const val TOP_GAP_DP = 25.6

/** `SubScreen.gd:140` `Column.separation = 22` → 8.8dp（顶栏与副标题之间）。 */
private const val BLOCK_GAP_DP = 8.8

/** `SubScreen.gd:145` `TopBar.separation = 20` → 8dp（返回钮与标题之间）。 */
private const val TOPBAR_GAP_DP = 8.0

/** `SubScreen.gd:182` `content.separation = 12` → 4.8dp（副标题、播放全部、空态、曲目列表之间）。 */
private const val ROW_GAP_DP = 4.8

/** `PlaylistScreen.gd:46` `_list.separation = 6` → 2.4dp（曲目行之间）。 */
private const val LIST_GAP_DP = 2.4

/** `PlaylistScreen.gd:29` `_play_all.custom_minimum_size = Vector2(0, 120)` → 48dp。 */
private const val BUTTON_HEIGHT_DP = 48

/**
 * 歌单详情整页浮层，移植自 `scripts/ui/PlaylistScreen.gd`（`extends SubScreen`）。
 *
 * 骨架逐项照抄 `SubScreen.gd:121-183`：`Backdrop`(色 `BG`) → `Margin`（左右 `PAD_SCREEN`、
 * 上 64px、下 `PAD_SCREEN`）→ `Column`(间距 22px) → `TopBar`（返回圆钮 96×96 + 标题）→
 * 副标题 → `Scroll`(`EXPAND_FILL`) → `content` `VBox`(间距 12px)。所以 **TopBar 与副标题
 * 不随内容滚动**，只有下面的播放按钮 / 空态 / 曲目列表滚动（原版 `Scroll` 只包住 `content`）。
 *
 * 标题与副标题（`PlaylistScreen.gd:56-61`）：
 * - `title_label.text = TextGuard.mask(歌单名，缺省 "歌单")`：歌单名可能来自接口，屏蔽词在这一层挡掉。
 * - `subtitle_label` = `["%d 首"]`，私密时追加 `私密`，最后追加 `点「播放全部」把整个歌单排进队列`，
 *   三段用 ` · ` 连接（非私密时中间那段整段消失，不是留空）。
 * - 数字是 `Blacklist.filter_songs` **之后**的条数，「播放全部」与行点击都传这份过滤后的列表。
 *
 * 本层自带不透明底色 —— 它盖在壳层之上，身后没有任何可依赖的底。
 * 关闭走 `LocalShell.current.handleBack()`（弹栈顶），而不是一个回调参数：
 * 签名里没有 close 回调，且 `handleBack` 弹的正是「最上面那一层」。
 *
 * 两条 Kotlin 侧**多出来**、原版没有的行为（不在本次还原范围内，保留原样）：
 * - 长按行弹 `SongActionSheet`（原版 `PlaylistScreen.gd:85-91` 的 `_make_row` 只连了 `tapped`）。
 * - 行右侧的「+」加进播放列表（用户 m05292 项⑤⑥）。
 */
@Composable
fun PlaylistOverlay(playlistId: String) {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors

    // 歌单内容会变（加歌/删歌/收藏变化），三个源都要订：歌单库、收藏投影、黑名单。
    val playlists by app.playlists.playlists.collectAsStateWithLifecycle()
    val favorites by app.favorites.songs.collectAsStateWithLifecycle()
    val blacklist by app.blacklist.entries.collectAsStateWithLifecycle()
    val current by app.playback.current.collectAsStateWithLifecycle()

    // 「我的收藏」永不落盘、是 Favorites 的投影，所以要用收藏流现算；
    // 「今日推荐」在 all() 里；自建歌单在 all() 里。找不到就回落到 find()。
    val playlist = when (playlistId) {
        Playlists.FAVORITES_ID -> app.playlists.favoritesPlaylist()
            .copy(songs = favorites, trackCount = favorites.size)
        else -> playlists.firstOrNull { it.id == playlistId } ?: app.playlists.find(playlistId)
    }
    // 黑名单变化不改歌单内容，但过滤结果会变：把 blacklist 并进 key，两者任一变了才重算。
    val songs = remember(playlist, blacklist) {
        app.blacklist.filterSongs(playlist?.songs ?: emptyList())
    }

    val title = TextGuard.mask(playlist?.name ?: "歌单")
    val subtitle = buildString {
        append(songs.size).append(" 首")
        if (playlist?.isPrivate == true) append(" · 私密")
        append(" · 点「播放全部」把整个歌单排进队列")
    }

    // 点歌/播放全部都是同一条链：排进队列 → 收掉本层 → 开播放页（原版 play_song/close/open_player）。
    fun playAndOpen(song: Track, list: List<Track>) {
        app.playback.playSong(song, list)
        shell.handleBack()
        shell.openPlayer()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            // 原版 Margin：左右 PAD_SCREEN、上 64px→25.6dp、下 PAD_SCREEN→16dp（`SubScreen.gd:132-135`）。
            .padding(
                start = Dims.PAD_SCREEN.dp,
                end = Dims.PAD_SCREEN.dp,
                top = TOP_GAP_DP.dp,
                bottom = Dims.PAD_SCREEN.dp,
            ),
        // 原版 Column separation 22px → 8.8dp（`SubScreen.gd:140`）。
        verticalArrangement = Arrangement.spacedBy(BLOCK_GAP_DP.dp),
    ) {
        // ---- TopBar：返回圆钮 + 标题（原版 `title_label`：FONT_TITLE/TEXT、EXPAND_FILL）----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            // 原版 TopBar HBox separation 20px → 8dp（`SubScreen.gd:145`）。
            horizontalArrangement = Arrangement.spacedBy(TOPBAR_GAP_DP.dp),
        ) {
            // 壳层的 FullOverlay 只负责淡出，返回钮得自己叫一次 handleBack()，否则用户没有可见的退路。
            RoundIconButton(
                icon = "chevron_down",
                contentDescription = "返回",
                onClick = { shell.handleBack() },
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

        // ---- 副标题（原版 `subtitle_label`：FONT_META/TEXT_DIM、AUTOWRAP_WORD_SMART）----
        Text(text = subtitle, color = c.textDim, fontSize = Typo.META.sp)

        // ---- Scroll → content VBox（原版 separation 12px → 4.8dp）----
        // 顶部补 ROW_GAP，补齐「Column separation 只出现在相邻子项之间」的缺口
        // （副标题与第一项之间原版也有 12px）。
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = ROW_GAP_DP.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        ) {
            // content 里按原版顺序摆：「播放全部」→ 空态标签 → 曲目列表；
            // 前两者由 `visible` 互斥（`PlaylistScreen.gd:25-47` 建，`:62-63` 切换）。
            // Compose 没有「隐藏但占位」的容器，`visible = false` 的写法只能落成 `if`。
            if (songs.isEmpty()) {
                // 原版 `Card.make_message` 是 140px(56dp) 高、`FONT_BODY`/`TEXT_DIM` 的居中 Label；
                // Kotlin 侧没有等价组件（`MessageCard` 是带发丝描边的卡片，见 `Components.kt:70`），
                // 按项目既有口径（`COMPOSE_CONVENTIONS.md` 的空态统一用 `MessageCard`）沿用。
                MessageCard("这个歌单还没有歌")
            } else {
                ActionButton(
                    text = "▶  播放全部",
                    primary = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BUTTON_HEIGHT_DP.dp),
                    onClick = { playAndOpen(songs.first(), songs) },
                )

                // 等价原版 `_list` 这个 VBoxContainer：它自己的 separation 是 6px→2.4dp，
                // 比 content 的 12px 更紧（`PlaylistScreen.gd:44-47`）。
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(LIST_GAP_DP.dp),
                ) {
                    songs.forEach { song ->
                        SongRow(
                            track = song,
                            playing = current?.id == song.id && song.id != 0L,
                            onClick = { playAndOpen(song, songs) },
                            onLongClick = { shell.showSongActions(song) },
                            // 用户 m05292 项⑥：每行右侧的「+」→ 播放列表选择器 → 加进去并按当前播放模式起播。
                            onAdd = { shell.addToQueueList(song) },
                        )
                    }
                }
            }
        }
    }
}
