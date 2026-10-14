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
import com.pullup.player.data.QueuePlaylist
import com.pullup.player.data.QueuePlaylists
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
 * 歌曲行右侧「+」弹出的**播放列表**选择器（用户 m05292 项⑤⑥）。
 *
 * 与 [AddToPlaylistSheet]（歌单选择器）的区别：
 * - 那边列的是**歌单**，加完把整张歌单排成队列从这首开始放；
 * - 这里列的是**播放列表**（可保存的待播队列），选一个列表把歌放进去，
 *   **同样按当前播放模式起播**，但不重置随机/单曲/列表（`PlaybackController.playSong`
 *   只换队列，不碰 playMode/shuffle）。
 *
 * 已经在该列表里的那一首不算失败：加不进去是空操作，但用户点它的意图很明确
 * ——「我要听这个列表、从这首开始」，所以照样起播。
 *
 * 面板造型逐条对齐原版 `ActionSheet.gd`：内边距左右 30px→12dp、上下 26px→10.4dp、
 * 行间距 4px→7.2dp、行底恒 `surfaceHi`、按下整块换 `line`、无 Material 涟漪。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToQueueListSheet(song: Track, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors

    val lists by app.queuePlaylists.lists.collectAsStateWithLifecycle()

    /** 点一条播放列表：加进去（可能已经在）→ 用它的完整列表当队列、从这首开始 → 开播放页。 */
    fun pick(pl: QueuePlaylist) {
        // 加不进去只有一种原因：已经在里面了（正常，接着放就行）。
        app.queuePlaylists.add(pl.id, song)
        // 加完再取一次：队列要用**加完之后**的列表，否则这首可能不在队列里。
        val target = app.queuePlaylists.find(pl.id) ?: return
        if (target.songs.isEmpty()) return
        // 最近用过的排前面，下次点「+」更快选到它。
        app.queuePlaylists.pinToTop(pl.id)
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
                text = "把这首放进哪个播放列表？",
                color = c.textDim,
                fontSize = Typo.META.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 行与行之间 10px = 4dp（`ActionSheet.gd:178` 的 `_actions.separation`）。
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 空态那一行是「建列表 + 加歌 + 起播」一步到位的入口：
                // 先 dismiss 再建，免得新建触发列表流变化、面板在收起动画中途重铺。
                if (lists.isEmpty()) {
                    PickRow("还没有播放列表，先新建一个", muted = true) {
                        onDismiss()
                        val pl = app.queuePlaylists.create(QueuePlaylists.DEFAULT_NAME)
                        pick(pl)
                    }
                } else {
                    lists.forEach { pl ->
                        PickRow(queueLabel(pl)) { pick(pl) }
                    }
                }
                PickRow("新建播放列表") {
                    onDismiss()
                    val pl = app.queuePlaylists.create(QueuePlaylists.DEFAULT_NAME)
                    pick(pl)
                }
                PickRow("取消", muted = true) { onDismiss() }
            }
        }
    }
}

/** 「名字 · N 首」—— 与 [AddToPlaylistSheet.playlistLabel] 同口径。 */
private fun queueLabel(pl: QueuePlaylist): String = buildString {
    append(pl.name.ifEmpty { "未命名播放列表" })
    append(" · ")
    append(pl.songs.size)
    append(" 首")
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
