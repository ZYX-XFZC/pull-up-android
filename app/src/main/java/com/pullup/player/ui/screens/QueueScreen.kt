package com.pullup.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.components.MessageCard
import com.pullup.player.ui.components.RoundIconButton
import com.pullup.player.ui.components.SongRow
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.pullShape

/**
 * 播放队列页。移植自 Godot 版 `scripts/ui/QueueScreen.gd`（`extends SubScreen`）。
 *
 * **「播放列表」＝正在播放的队列**（原版 `PlayerManager.playlist`），不是首页里那些自建歌单 ——
 * 用户 m06483/m06486 的原话是「播放列表在哪？」，指的就是这一条队列。
 *
 * 页面不持有副本：队列本来就活在 `PlaybackController.queue` 里，这里只订阅它，
 * 队列一变整页自动重铺。原版要靠 `playlist_changed` 信号 + `_rebuild.call_deferred()`
 * 延迟重铺，是因为它要在信号发射栈里拆掉发射者（「×」自己就是发信号的那颗按钮）；
 * Kotlin 侧状态变化由 Compose 重组完成，删除当前行的按钮不会拆掉自己脚下的栈，故不需要那层去抖。
 *
 * 逐条对应原版：
 * - `:44` 顶部「清空播放列表」行，副标题「把队列里的歌全部拿掉」，`count > 0` 时才显示；
 * - `:48` 空态 `Card.make_message("队列里还没有歌")`；
 * - `:75-77` 标题「播放列表」，副标题「当前队列 · N 首 · 点一首跳过去播」/「当前队列是空的」；
 * - `:96` 行用 `make_song_row(song, false)` —— **with_add=false**，队列里的歌后面不再挂「＋」
 *   （原版注释：再加一次只会回「播放列表里已经有了」）；
 * - `:99` 当前行高亮；
 * - `:100-104` **只有 `index != current_index and size > 1`** 才挂行尾「×」——
 *   这两种情况 `remove_from_queue()` 都会拒绝，「挂一颗按不动的按钮不如不挂」；
 * - `:158` 点行 → `play_at_index(index)` + 收起 + 开播放页。
 *
 * SubScreen 骨架逐项照抄（`SubScreen.gd:189-240`）：Backdrop(BG) → Margin（左右 PAD_SCREEN、
 * 上 64px→25.6dp、下 PAD_SCREEN）→ Column(spacing 22px→8.8dp) → TopBar → 副标题 →
 * Scroll(EXPAND_FILL) → content VBox(spacing 12px→4.8dp)。
 * 所以 **TopBar 与副标题不随内容滚动**，只有下面的行滚动。
 */
@Composable
fun QueueScreen(onClose: () -> Unit) {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors

    // 订阅队列与当前曲目：任何一个变了都要重新铺行（原版连的是 `playlist_changed` + `song_changed`）。
    val queue by app.playback.queue.collectAsStateWithLifecycle()
    val current by app.playback.current.collectAsStateWithLifecycle()
    val currentId = current?.id

    // 等价原版 `get_current_index()`：拿当前曲的 id 去队列里现查，而不是另存一份下标。
    val currentIndex = if (currentId == null) -1 else queue.indexOfFirst { it.id == currentId }
    val count = queue.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            // SubScreen 骨架 Margin：左右 PAD_SCREEN、上 64px→25.6dp、下 PAD_SCREEN。
            .padding(start = Dims.PAD_SCREEN.dp, end = Dims.PAD_SCREEN.dp, top = 25.6.dp, bottom = 16.dp),
        // SubScreen 骨架 Column separation 22px → 8.8dp。
        verticalArrangement = Arrangement.spacedBy(8.8.dp),
    ) {
        // ---- TopBar：返回圆钮（96px→38.4dp）+ 标题（FONT_TITLE/TEXT）----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            // SubScreen TopBar HBox separation 20px → 8dp。
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundIconButton(
                icon = "chevron_down",
                contentDescription = "收起",
                onClick = onClose,
            )
            Text(
                // 原版 `_rebuild()` 里写死 `title_label.text = "播放列表"`（`:73`）。
                text = "播放列表",
                color = c.text,
                fontSize = Typo.TITLE.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ---- 副标题（原版 `:75-77`：有歌报数量，没歌报空）----
        Text(
            text = if (count > 0) "当前队列 · $count 首 · 点一首跳过去播" else "当前队列是空的",
            color = c.textDim,
            fontSize = Typo.META.sp,
        )

        // ---- Scroll → content VBox（原版 separation 12px → 4.8dp）----
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.8.dp),
        ) {
            // 行：清空播放列表（原版 `:44`，`_clear_btn.visible = count > 0`）
            if (count > 0) {
                QueueActionRow(
                    title = "清空播放列表",
                    hint = "把队列里的歌全部拿掉",
                    onClick = { app.playback.clearQueue() },
                )
            }

            if (count == 0) {
                // 空态（原版 `:48`）
                MessageCard("队列里还没有歌")
            } else {
                // 列表（原版 `_list` 是 VBoxContainer、separation 6px → 2.4dp）
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.4.dp),
                ) {
                    queue.forEachIndexed { index, track ->
                        // 行内右侧「×」的位置：原版 `_shift_row_content()` 把行的 `margin_right`
                        // 加宽 `REMOVE_SIZE + BUTTON_GAP` = 134px = 53.6dp 给按钮让位；
                        // Compose 侧等价做法是让行只占剩下的宽度，按钮作为兄弟节点排在右端。
                        val showRemove = index != currentIndex && count > 1
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                SongRow(
                                    track = track,
                                    playing = track.id == currentId,
                                    onClick = {
                                        // 先跳过去播再开播放页：原版就是这个顺序（`:158-164`）。
                                        app.playback.playAtIndex(index)
                                        onClose()
                                        shell.openPlayer()
                                    },
                                    // 队列行不挂长按菜单，也不挂「＋」（原版 `with_add=false`）。
                                )
                            }
                            if (showRemove) {
                                // 原版 `_shift_row_content()` 的 margin 里已经含 ROW_PAD 14px，
                                // 按钮左边再留 BUTTON_GAP 14px → 合计 5.6dp + 5.6dp。
                                Spacer(Modifier.width(5.6.dp))
                                QueueRemoveButton(
                                    onClick = { app.playback.removeFromQueue(index) },
                                )
                                Spacer(Modifier.width(5.6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * `SubScreen.make_row(title, hint)` 的 Compose 对照（本页只需要「清空」这一行）。
 *
 * 逐项对齐 `SubScreen.gd:144-186`：最小高 128px→51.2dp；`card_box(RADIUS_SMALL)`
 * （卡片底 + 发丝边）；内边距左右 34px→13.6dp；HBox 间距 18px→7.2dp；
 * 左侧 VBox（标题 `FONT_BODY`/`TEXT`、提示 `FONT_META`/`TEXT_DIM`，间距 4px→1.6dp）；
 * 尾部箭头是 **`♪`**（`FONT_SUB`/`TEXT_DIM`）—— 这是 `SubScreen.make_row` 的记号，
 * 不用于 `Components.SettingRow` 那个 `›`。
 */
@Composable
private fun QueueActionRow(title: String, hint: String, onClick: () -> Unit) {
    val c = PullTheme.colors
    val shape = pullShape(Dims.RADIUS_SMALL)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 51.2.dp)
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.2.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.6.dp),
        ) {
            Text(text = title, color = c.text, fontSize = Typo.BODY.sp)
            if (hint.isNotEmpty()) {
                Text(text = hint, color = c.textDim, fontSize = Typo.META.sp)
            }
        }
        Text(text = "♪", color = c.textDim, fontSize = Typo.SUB.sp)
    }
}

/**
 * 行尾的「×」：把这一首从队列里拿掉（原版 `QueueScreen.gd:123 _make_remove_button()`）。
 *
 * 几何：`REMOVE_SIZE := 120` px → **48dp**（触控下限）；字号 `FONT_TITLE`(50px)→20sp；
 * 底是 `AppTheme.button_box(SURFACE, RADIUS_SMALL)` —— **圆角矩形不是胶囊**
 * （原版 `:134` 注释：`-- --style` 只允许播放键是胶囊）。
 * 三态配色照抄：常态 `SURFACE`/`TEXT_DIM`、按下 `ACCENT`/`ON_ACCENT`（原版还有一个 hover 态，
 * 触屏用不上）。
 *
 * 它是行的**兄弟节点而不是子节点**，所以点「×」不会连带触发整行的「跳过去播」
 * —— 等价原版 `mouse_filter = STOP`。
 */
@Composable
private fun QueueRemoveButton(onClick: () -> Unit) {
    val c = PullTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(if (pressed) c.accent else c.surface)
            .clickable(
                interactionSource = interaction,
                // 关掉涟漪：原版的按压反馈就是整块换色，不叠水波。
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = "从播放列表移除" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "×",
            color = if (pressed) c.onAccent else c.textDim,
            fontSize = Typo.TITLE.sp,
        )
    }
}
