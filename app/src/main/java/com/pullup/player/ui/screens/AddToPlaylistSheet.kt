package com.pullup.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.data.LocalPlaylist
import com.pullup.player.data.TextGuard
import com.pullup.player.data.Track
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.pullShape

/**
 * 行高与原版二级面板同档：`ActionSheet.gd:18,22` 的 `BLACKLIST_ROW_HEIGHT`/`PICKER_ROW_HEIGHT`
 * 都是 72px ⇒ 72 ÷ 2.5 = **28.8dp**（不是取整的 30）。
 */
private const val ROW_HEIGHT_DP = 28.8f

/**
 * 歌曲行右侧「+」弹出的歌单选择器。
 *
 * 与 [SongActionSheet] 的「添加到歌单」二级面板**不是同一件事**：那边加完就收工，
 * 这里加完**立刻把整张歌单排成队列、从这首开始放**，并沿用当前播放模式
 * （不重置随机/单曲/列表 —— `PlaybackController.playSong` 只换队列，不碰 playMode/shuffle）。
 *
 * 已经在该歌单里的那一首不算失败：加不进去是空操作，但用户点它的意图很明确
 * ——「我要听这张歌单、从这首开始」，所以照样起播，只是提示一句已经在了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(song: Track, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors

    val playlists by app.playlists.playlists.collectAsStateWithLifecycle()
    val options = remember(playlists) { app.playlists.addable() }

    /** 点一张歌单：加进去（可能已经在）→ 用它的完整歌曲列表当队列、从这首开始 → 开播放页。 */
    fun pick(pl: LocalPlaylist) {
        // 加不进去只有两种原因：已经在里面了（正常，接着放就行），或这首歌没有 id
        // （`Playlists.addSong` 按原版口径拒收本地负 id）。两种都不该拦着起播。
        app.playlists.addSong(pl.id, song)
        // 加完再取一次：队列要用**加完之后**的列表，否则这首会不在队列里。
        val target = app.playlists.find(pl.id) ?: return
        if (target.songs.isEmpty()) return
        app.playback.playSong(song, target.songs)
        onDismiss()
        shell.openPlayer()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        scrimColor = c.scrim.copy(alpha = 0.42f),
        shape = pullShape(Dims.RADIUS),
        dragHandle = null,
    ) {
        // 面板内边距与 `ActionSheet.gd:155-158` 的 Margin 一致：左右 30px、上下 26px。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.4.dp),
            verticalArrangement = Arrangement.spacedBy(7.2.dp),
        ) {
            // 标题/副标题是 Column 的直接子项（`ActionSheet.gd:166-174`）——原版面板里没有封面行。
            Text(
                text = TextGuard.mask(song.name),
                color = c.text,
                fontSize = Typo.SUB.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "把这首放进哪个歌单？",
                color = c.textDim,
                fontSize = Typo.META.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 行与行之间 10px = 4dp（`ActionSheet.gd:178` 的 `_actions.separation`）。
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (options.isEmpty()) {
                    PickRow("还没有自建歌单，先新建一个", muted = true) {}
                } else {
                    options.forEach { pl ->
                        PickRow(playlistLabel(pl)) { pick(pl) }
                    }
                }
                PickRow("新建歌单") {
                    onDismiss()
                    // 带着这首歌进编辑页：建好就自动放进去（原版 `ActionSheet.gd:248-258`）。
                    shell.openEditorFor(song)
                }
                PickRow("取消", muted = true) { onDismiss() }
            }
        }
    }
}

/** 「名字 · N 首」(+「 · 私密」)—— 与 `SongActionSheet.playlistLabel` 同口径。 */
private fun playlistLabel(pl: LocalPlaylist): String = buildString {
    append(pl.name.ifEmpty { "未命名歌单" })
    append(" · ")
    append(pl.trackCount.coerceAtLeast(pl.songs.size))
    append(" 首")
    if (pl.isPrivate) append(" · 私密")
}

/**
 * 左对齐的一行，逐条对齐 `ActionSheet.gd` 的 `_add_action()`（`:307-317`）：
 *
 * - 底色**恒为** `SURFACE_HI`（`button_box(SURFACE_HI)`，`AppTheme.gd:550`）；只有**字色**做三元
 *   （`:314-315` 的 `font_color` 才判 muted），旧版把 muted 行也换成 `c.card` 是错的。
 * - **没有左右内边距**（`button_box` = `panel_box`，`pad` 缺省 0 ⇒ 文字贴左缘，`AppTheme.gd:580-581`）。
 * - 按下态是**整块底色换成 `LINE`**（`:317`），不是 Material 涟漪。
 */
@Composable
private fun PickRow(label: String, muted: Boolean = false, onClick: () -> Unit) {
    val c = PullTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT_DP.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(if (pressed) c.line else c.surfaceHi)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            color = if (muted) c.textDim else c.text,
            fontSize = Typo.BODY.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
