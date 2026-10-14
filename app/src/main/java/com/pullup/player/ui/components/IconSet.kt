package com.pullup.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.pullup.player.R

/**
 * 图标集：`res/drawable-nodpi/<name>.png`（白色图形 + 透明底），带缓存。
 * 移植自 `scripts/ui/IconSet.gd` + `scripts/ui/IconView.gd`。
 *
 * 原版这些图是从一整张 1254×1254 的图里按坐标裁出来的（`scripts/dev/IconLabeller.gd`），
 * 20 张 256×256 的 PNG 共 115,183 B。**取不到图时退回兜底绘制，图缺了也不会白屏**
 * （`IconSet.gd:6`、`IconView.gd:36-37`：兜底画一个半透明圆点）。
 *
 * 为什么值得把 PNG 搬过来：Material 的 `Icons.Rounded.*` 是另一套笔画与光学尺寸，
 * 原版所有按钮/导航/空态图标都是这套图 —— 用 Material 图标会让「重构」变成「重新设计」，
 * 而用户 m02624 明确要求恢复原样。
 *
 * 尺寸口径（`IconView.gd:24,32-33`）：图标占控件**短边**的 `ratio`（默认 0.52），居中。
 */
private const val DEFAULT_RATIO = 0.52f

/** 图标名 → drawable 资源 id。名字与原版 `assets/icons_v2/<name>.png` 一一对应。 */
private fun iconRes(name: String): Int? = when (name) {
    "add" -> R.drawable.add
    "album" -> R.drawable.album
    "chevron_down" -> R.drawable.chevron_down
    "heart" -> R.drawable.heart
    "heart_filled" -> R.drawable.heart_filled
    "home" -> R.drawable.home
    "library" -> R.drawable.library
    "more" -> R.drawable.more
    "next" -> R.drawable.next
    "pause" -> R.drawable.pause
    "play" -> R.drawable.play
    "prev" -> R.drawable.prev
    "repeat" -> R.drawable.repeat
    "repeat_off" -> R.drawable.repeat_off
    "repeat_one" -> R.drawable.repeat_one
    "search" -> R.drawable.search
    "settings" -> R.drawable.settings
    "shuffle" -> R.drawable.shuffle
    "shuffle_off" -> R.drawable.shuffle_off
    "timer" -> R.drawable.timer
    else -> null
}

/** 这套图标是否存在（对应 `IconSet.gd:26` 的 `has`）。 */
fun hasIconAsset(name: String): Boolean = iconRes(name) != null

/**
 * 把图标集里的一张图居中画出来，并按 [color] 染色（原图是纯白，可以随便染）。
 *
 * 找不到 [name] 对应的图时画一个半透明圆点 —— 与 `IconView.gd:36-37` 的兜底一致，
 * 保证任何情况下都不是白块。
 *
 * [modifier] 决定图标占据的方框；图标在其中按短边的 [ratio] 居中。
 */
@Composable
fun IconAsset(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    ratio: Float = DEFAULT_RATIO,
) {
    val res = iconRes(name)
    if (res != null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(res),
                contentDescription = null,
                colorFilter = ColorFilter.tint(color),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    // 兜底：画一个圆点，至少不是空白（`IconView.gd:36-37`）。
    Canvas(modifier = modifier) {
        val side = minOf(size.width, size.height) * ratio
        drawCircle(
            color = color.copy(alpha = 0.5f),
            radius = side * 0.18f,
            center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.5f),
        )
    }
}
