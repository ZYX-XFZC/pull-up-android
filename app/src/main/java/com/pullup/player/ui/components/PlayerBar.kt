package com.pullup.player.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.Motion
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo

/**
 * 迷你播放条，移植自 `scripts/ui/PlayerBar.gd`。
 *
 * 三条易错点，都照原注释保留：
 * - **整条都是点击区**（`MOUSE_FILTER_STOP` + `gui_input`），点空白打开全屏播放页；
 *   三颗按钮自己吃掉事件（`PlayerBar.gd:46-48, 217-221`）。
 * - **进度画在条的最下沿**，不用 Slider —— 原版用 HSlider 时被 PanelContainer
 *   布局抢走位置，滑块跑到左上角压住封面（`PlayerBar.gd:5-8`）。
 * - 数据全部来自状态、不轮询。
 *
 * 几何全部是原稿 px ÷ 2.5（真源 `AppTheme.gd:64` 逐字「1080px = 432dp，即 1dp = 2.5px」）：
 * 高 **200px = 80dp**（真源在**场景**里：`scenes/main.tscn:94-95` `offset_top = -360` /
 * `offset_bottom = -160`，脚本 `PlayerBar.gd:3` 自证「80dp = 200px 单行」）；
 * 封面 `ART_SIZE = 120px` = 48dp、圆角 `ART_RADIUS = 14px` = 5.6dp（取整 6dp）；
 * 左内边距 18px = 7.2dp、右 25px = 10dp、行间距 26px = 10.4dp；信息列间距 6px = 2.4dp；
 * 控制组间距 12px = 4.8dp；三颗按钮 `PREV/NEXT 84px` = 33.6dp、`PLAY/PAUSE 104px` = 41.6dp
 * （`PlayerBar.gd:22-28, 79-141`）。
 *
 * 注意：不要把这里的 80dp / 48dp / 34dp 再除一次 2.5。原稿的 `PlayerBar.gd:3` 注释写的
 * 「80dp」和 `ART_SIZE = 120.0` 是**两种单位**混在同一个文件里：前者已是 dp，后者是 px。
 * 只有 px 才除以 2.5。
 */
@Composable
fun PlayerBar(
    title: String,
    subtitle: String,
    cover: String,
    playing: Boolean,
    progress: Float,
    onClick: () -> Unit,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    val c = PullTheme.colors
    val shape = RoundedCornerShape(Dims.RADIUS_SMALL.dp)
    // 原版整条没有任何按压反馈（`gui_input` 只发信号，不改外观），故不吃 ripple。
    val barInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT_DP.dp)
            .clip(shape)
            .background(c.card)
            // 背板 = `AppTheme.card_box(RADIUS_SMALL)`：CARD 填充 + **2px 发丝描边 LINE**
            // + 大圆角（`PlayerBar.gd:49` → `AppTheme.gd:563-567`）。
            // 2px 按本工程既有口径落成 1dp（见 COMPOSE_CONVENTIONS「卡片 = 半透明填充 +
            // 1dp 发丝描边 + 大圆角」，与 Components.kt:77 等十余处同例）。
            .border(1.dp, c.line, shape)
            .clickable(
                interactionSource = barInteraction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT_DP.dp)
                .padding(start = ROW_PAD_START_DP.dp, end = ROW_PAD_END_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
        ) {
            Cover(url = cover, size = ART_DP, corner = ART_RADIUS_DP)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(INFO_GAP_DP.dp),
            ) {
                // 原版每个 Label 都有最小行高 `ceil(font_size × 1.45)`（`PlayerBar.gd:167`），
                // 行盒比字形高，两行之间才不会挤在一起。
                Text(
                    text = title,
                    color = c.text,
                    fontSize = Typo.BODY.sp,
                    lineHeight = (Typo.BODY * INFO_LINE_RATIO).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = c.textDim,
                    fontSize = Typo.SUB.sp,
                    lineHeight = (Typo.SUB * INFO_LINE_RATIO).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CONTROLS_GAP_DP.dp),
            ) {
                // 三颗按钮都 `flat = true`（只画图标、不套底色），且**都是 TEXT 色** ——
                // 原版没有给播放键覆盖 font_color，它和上/下一首同色（`PlayerBar.gd:147-156`
                // → `AppTheme.gd:608` Button font_color = TEXT）。
                BarIcon("prev", "上一首", SIDE_BUTTON_DP, onPrev)
                BarIcon(
                    icon = if (playing) "pause" else "play",
                    description = if (playing) "暂停" else "播放",
                    size = PLAY_BUTTON_DP,
                    onClick = onToggle,
                )
                BarIcon("next", "下一首", SIDE_BUTTON_DP, onNext)
            }
        }

        // 进度：条的最下沿，**直角矩形**（原版是两条 `draw_rect`，不是圆头线）。
        // 线高 `PROGRESS_LINE = 5px` = 2dp，左右各内缩 `PROGRESS_INSET = 50px` = 20dp，
        // 矩形下沿在 `y + half = size.y - 4px` ⇒ 距条底 4px = 1.6dp（`PlayerBar.gd:206-214`）。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = PROGRESS_INSET_DP.dp)
                .padding(bottom = PROGRESS_BOTTOM_DP.dp)
                .height(PROGRESS_LINE_DP.dp)
                // 未播段 `Color(AppTheme.ICON, 0.10)`（`PlayerBar.gd:211`）。
                .background(c.icon.copy(alpha = 0.10f)),
        ) {
            // 原版只在 `played > 0.5px` 时才画已播段（`PlayerBar.gd:213`）；条宽上千 px，
            // 该阈值等价于「进度非零」，故此处按 `progress > 0` 处理。
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(PROGRESS_LINE_DP.dp)
                        .background(c.accent),
                )
            }
        }
    }
}

/**
 * 条高 200px = 80dp。真源是**场景节点**而非脚本常量：
 * `scenes/main.tscn:94-95` 的 `offset_top = -360.0` / `offset_bottom = -160.0`，
 * 差值 200px；脚本 `PlayerBar.gd:3` 注释自证「80dp = 200px 单行」。
 */
private const val BAR_HEIGHT_DP = 80

/** 封面 `ART_SIZE = 120px` = 48dp（`PlayerBar.gd:22`）。 */
private const val ART_DP = 48

/** 封面圆角 `ART_RADIUS = 14px` = 5.6dp（`PlayerBar.gd:24`）；`Cover` 的入参是 Int，取整 6dp。 */
private const val ART_RADIUS_DP = 6

/** 行内边距左 18px = 7.2dp、右 25px = 10dp；封面↔信息列间距 26px = 10.4dp（`PlayerBar.gd:83-84, 89`）。 */
private const val ROW_PAD_START_DP = 7.2f
private const val ROW_PAD_END_DP = 10f
private const val ROW_GAP_DP = 10.4f

/** 信息列行间距 6px = 2.4dp（`PlayerBar.gd:123`）。 */
private const val INFO_GAP_DP = 2.4f

/** 行盒高 = 字号 × 1.45（`PlayerBar.gd:167` `ceilf(size * 1.45)`）。 */
private const val INFO_LINE_RATIO = 1.45f

/** 控制组：`PREV/NEXT 84px` = 33.6dp，`PLAY/PAUSE 104px` = 41.6dp（`PlayerBar.gd:138-141`）。 */
private const val SIDE_BUTTON_DP = 33.6f
private const val PLAY_BUTTON_DP = 41.6f

/** 控制组按钮间距 12px = 4.8dp（`PlayerBar.gd:134`）。 */
private const val CONTROLS_GAP_DP = 4.8f

/** 进度线高 5px = 2dp、左右内缩 50px = 20dp、距条底 4px = 1.6dp（`PlayerBar.gd:26-28, 207-214`）。 */
private const val PROGRESS_LINE_DP = 2
private const val PROGRESS_INSET_DP = 20
private const val PROGRESS_BOTTOM_DP = 1.6f

/**
 * 图标边长占按钮短边的比例。原版有两条路径（`TransportButton.gd:88-94`）：
 * 手绘用 `GLYPH_RATIO = 0.34`（画出 2×half = 0.68），但**图标集存在时优先走图标集**，
 * 那条路径按 `minf(size) × 0.72` 画并提前 return。`assets/icons_v2/` 下
 * `play.png` / `pause.png` / `prev.png` / `next.png` 都在，所以线上真正生效的是 **0.72**
 * （原注释：图标集是满格画的，0.72 才和手绘笔触视觉重量接近）。
 */
private const val GLYPH_RATIO = 0.72f

/** 按压缩放补间：`AppTheme.PRESS_IN = 0.09s` / `PRESS_OUT = 0.18s`（`AppTheme.gd:95-97`）。 */
private const val PRESS_IN_MS = 90
private const val PRESS_OUT_MS = 180

/**
 * 条上的传输按钮：`flat = true` 只画图标、无底色、无 ripple；按压时缩到
 * `Motion.PRESS_SCALE`（0.88）再回弹，对应 `TransportButton.gd:41-52` 的按下/松开补间。
 */
@Composable
private fun BarIcon(
    icon: String,
    description: String,
    size: Float,
    onClick: () -> Unit,
) {
    val c = PullTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PRESS_SCALE else 1f,
        animationSpec = tween(durationMillis = if (pressed) PRESS_IN_MS else PRESS_OUT_MS),
        label = "barIconPress",
    )
    Box(
        modifier = Modifier
            .size(size.dp)
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        IconAsset(
            name = icon,
            color = c.text,
            modifier = Modifier.size((size * GLYPH_RATIO).dp),
        )
    }
}
