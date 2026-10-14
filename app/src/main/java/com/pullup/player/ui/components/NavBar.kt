package com.pullup.player.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pullup.player.ui.theme.PullTheme

/**
 * 底部导航，移植自 `scripts/ui/NavBar.gd`。
 *
 * - **只有 3 个 Tab**（原注释：搜索收进首页顶部，歌单并入首页，音乐库管收藏与本地）。
 *   `page` 取值沿用 MainUI.Page：首页 0 / 音乐库 2 / 设置 3。搜索（1）不是 Tab（`NavBar.gd:22-28`）。
 * - 几何（px ÷ 2.5 = dp/sp，与 `Dims` / `Typo` 同一换算）：栏高 `BAR_HEIGHT = 160px` = 64dp；
 *   图标 `ICON_SIZE = 65px` = 26dp；标签 `LABEL_SIZE = 30` = 12sp；单元内边距 top 20px = 8dp /
 *   bottom 18px = 7.2dp；图标与文字间距 8px = 3.2dp（`NavBar.gd:16-20, 77-84`）。
 * - 背板 `panel_box(SURFACE, 0)` + **顶沿 2px 发丝线**（`NavBar.gd:40-43`）。
 * - 选中态：选中项 `modulate.a = 1.0`、文字色 `ACCENT`；未选中 `0.4`、`TEXT_DIM`
 *   （`NavBar.gd:107-117`）。**图标恒为 `AppTheme.ICON`，不跟着选中态变色** —— 原版只给
 *   `label` 加了 `font_color` 覆盖，图标仅靠 1.0 / 0.4 的 modulate 区分（`NavIcon.gd:31`）。
 * - 顶部指示条：高 4px = 1.6dp、宽 = 该项宽度 50%、色 `ACCENT`，水平居中于该项之下；切换时
 *   位置与宽度同补间 `AppTheme.anim(0.18)`（`NavBar.gd:59-64, 121-132`）。
 * - 层级同原版：发丝线（panel 边框，最底层）→ 三个等宽 Tab（HBox 铺满整栏）→ 指示条（叠在最上）。
 */
private data class NavItem(val page: Int, val label: String, val icon: String)

// 图标名对应 `NavIcon.gd:54-64` 的 `_texture_name()`：首页 home / 音乐库 library / 设置 settings。
// 原版这三个图标走图标集（`NavIcon.gd:39` 先试图标集、取不到才手绘），不是 Material 图标。
private val NAV_ITEMS = listOf(
    NavItem(0, "首页", "home"),
    NavItem(2, "音乐库", "library"),
    NavItem(3, "设置", "settings"),
)

/** 栏高 160px ÷ 2.5 = 64dp（`NavBar.gd:16`）。 */
private const val BAR_HEIGHT_DP = 64

/** 图标 65px ÷ 2.5 = 26dp（`NavBar.gd:18`）；标签 30px ÷ 2.5 = 12sp（`:20`）。 */
private const val ICON_DP = 26
private const val LABEL_SP = 12

/** 单元内边距：margin_top 20px = 8dp / margin_bottom 18px = 7.2dp（`NavBar.gd:77-78`）。 */
private const val ITEM_PAD_TOP_DP = 8f
private const val ITEM_PAD_BOTTOM_DP = 7.2f

/** 图标与标签间距：separation 8px ÷ 2.5 = 3.2dp（`NavBar.gd:84`）。 */
private const val ICON_LABEL_GAP_DP = 3.2f

/** 指示条高 4px ÷ 2.5 = 1.6dp（`NavBar.gd:61`）。 */
private const val INDICATOR_DP = 1.6f

/** 顶沿发丝线：panel 的 border_width_top = 2px ÷ 2.5 = 0.8dp（`NavBar.gd:41`）。 */
private const val HAIRLINE_DP = 0.8f

/** 指示条补间基准时长：`AppTheme.anim(0.18)`（`NavBar.gd:131-132`）。 */
private const val INDICATOR_ANIM_SECONDS = 0.18f

/** 导航图标占短边 0.92（`NavIcon.gd:38` 的 `var side := s * 0.92`）。 */
private const val NAV_ICON_RATIO = 0.92f

@Composable
fun PullNavBar(current: Int, onSelect: (Int) -> Unit) {
    val c = PullTheme.colors
    val density = LocalDensity.current
    // 时长走 `AppTheme.anim()` 的等价物：随「动效缩放」设置一起伸缩，而不是写死 180ms。
    val indicatorAnimMs = PullTheme.state.anim(INDICATOR_ANIM_SECONDS)
    var rowWidthPx by remember { mutableIntStateOf(0) }

    val index = NAV_ITEMS.indexOfFirst { it.page == current }.coerceAtLeast(0)
    val itemWidthPx = if (rowWidthPx > 0) rowWidthPx / NAV_ITEMS.size else 0
    // 指示条：宽 = 该项宽度的 50%，居中于该项之下 ——
    // `x = button.position.x + (button.size.x − width) / 2`（`NavBar.gd:125-126`）。
    val offset by animateDpAsState(
        targetValue = with(density) { (itemWidthPx * index + itemWidthPx / 4).toDp() },
        animationSpec = tween(indicatorAnimMs),
        label = "navIndicatorOffset",
    )
    val indicatorWidth by animateDpAsState(
        targetValue = with(density) { (itemWidthPx / 2).toDp() },
        animationSpec = tween(indicatorAnimMs),
        label = "navIndicatorWidth",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT_DP.dp)
            .background(c.surface)
            .onSizeChanged { rowWidthPx = it.width },
    ) {
        // 最底层：栏顶 2px 发丝线（原版是 panel stylebox 的 border_width_top，只有顶边）。
        Box(Modifier.fillMaxWidth().height(HAIRLINE_DP.dp).background(c.line))

        // 中层：三个等宽 Tab 铺满整栏（原版 HBox 铺满 160px，不被指示条挤矮）。
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            NAV_ITEMS.forEach { item ->
                val active = item.page == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = ITEM_PAD_TOP_DP.dp, bottom = ITEM_PAD_BOTTOM_DP.dp)
                        // 选中 1.0 / 未选中 0.4：图标与文字一起降透明度（`NavBar.gd:112-113`）。
                        .alpha(if (active) 1f else 0.4f)
                        // 原版 Button 是 flat：无水波纹（`NavBar.gd:70`）。
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(item.page) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    IconAsset(
                        name = item.icon,
                        // 图标色恒为 ICON：原版只在 label 上覆盖 font_color，图标不变色
                        // （`NavIcon.gd:31` 的 `AppTheme.ICON`，`:39` 传的也是这个色）。
                        color = c.icon,
                        modifier = Modifier.size(ICON_DP.dp).semantics {
                            contentDescription = item.label
                        },
                        // 原版 `NavIcon.gd:38`：图标占短边 0.92（比 IconView 默认的 0.52 大得多）。
                        ratio = NAV_ICON_RATIO,
                    )
                    Spacer(Modifier.height(ICON_LABEL_GAP_DP.dp))
                    Text(
                        text = item.label,
                        color = if (active) c.accent else c.textDim,
                        fontSize = LABEL_SP.sp,
                    )
                }
            }
        }

        // 最上层：指示条压在栏顶，盖住发丝线（原版 `_indicator` 加在 `_row` 之后）。
        if (itemWidthPx > 0) {
            Box(
                modifier = Modifier
                    .offset(x = offset)
                    .width(indicatorWidth)
                    .height(INDICATOR_DP.dp)
                    .background(c.accent),
            )
        }
    }
}
