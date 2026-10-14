package com.pullup.player.ui.components

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmapOrNull
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.ImageRequest
import com.pullup.player.data.AppContainer
import com.pullup.player.ui.theme.Motion
import com.pullup.player.ui.theme.PullTheme

/**
 * 环境背景：底色 + 两团柔光。移植自 `scripts/ui/AmbientBackground.gd`。
 *
 * 原版的关键行为是**光斑颜色跟着当前歌曲的封面走**（取封面平均色，再派生一亮一暗两团），
 * 换歌时用 `AppTheme.ANIM_AMBIENT` 交叉淡入；自定义背景存在时优先用自定义背景取色，
 * 不再随封面变（`AmbientBackground.gd:146` 的 `_on_song_changed`）。
 *
 * 几何同样照抄：两团光斑的圆心在归一化坐标 (0.28, 0.18) 与 (0.78, 0.82)，
 * 半径 0.62 / 0.58（`AmbientBackground.gd:59,68`）。原版把 512×512 的正圆渐变贴图
 * 用 `STRETCH_SCALE` 拉满整屏，于是正圆变成**椭圆** —— 这里用 `scale` 把单位正方形
 * 拉成屏幕尺寸来复刻同一条衰减，而不是画正圆。
 *
 * 光斑不透明度分深浅两套（`AmbientBackground.gd:16-19`）：
 * 深色 0.35 / 0.42，浅色 0.55 / 0.60 —— 浅色画布要压得住，否则一片白看不出层次。
 *
 * 播放页是透明的，靠这一层透出环境光，所以它不能是纯色块。
 */
@Composable
fun AmbientBackground(app: AppContainer, modifier: Modifier = Modifier) {
    val c = PullTheme.colors
    val theme = PullTheme.state
    val context = LocalContext.current

    val track by app.playback.current.collectAsStateWithLifecycle()
    val customBackground by app.customArt.background.collectAsStateWithLifecycle()

    // 当前光斑来源图的平均色；null 表示「没有图」，走兜底色。
    var avg by remember { mutableStateOf<Color?>(null) }

    // 自定义背景优先（原版 `_on_song_changed` 首行就是这个判断）；否则用当前歌曲封面。
    val cover = track?.cover.orEmpty()
    LaunchedEffect(customBackground, cover) {
        avg = when {
            customBackground != null -> averageOfBitmap(customBackground!!)
            cover.isNotBlank() -> averageColor(context, cover)
            else -> null
        }
    }

    val isDark = theme.isDark
    // 深色拉开层次（向白提亮 0.30 / 向底色压暗 0.62）；浅色同色相提亮（0.55 / 0.20）。
    val light = avg?.let { if (isDark) lerp(it, Color.White, 0.30f) else lerp(it, Color.White, 0.55f) }
        ?: if (isDark) FALLBACK_A else FALLBACK_A_LIGHT
    val dark = avg?.let { if (isDark) lerp(it, c.bg, 0.62f) else lerp(it, c.bg, 0.20f) }
        ?: if (isDark) FALLBACK_B else FALLBACK_B_LIGHT

    // 原版 `set_song_colors` 用 Tween 交叉淡入两团光斑，时长 `AppTheme.ANIM_AMBIENT`。
    val spec = tween<Color>(theme.anim(Motion.ANIM_AMBIENT))
    val glowA by animateColorAsState(targetValue = light, animationSpec = spec, label = "ambientA")
    val glowB by animateColorAsState(targetValue = dark, animationSpec = spec, label = "ambientB")

    val alphaA = if (isDark) GLOW_ALPHA_A else GLOW_ALPHA_A_LIGHT
    val alphaB = if (isDark) GLOW_ALPHA_B else GLOW_ALPHA_B_LIGHT

    Box(modifier = modifier.fillMaxSize().background(c.bg)) {
        Canvas(Modifier.fillMaxSize()) {
            glow(center = Offset(0.28f, 0.18f), radius = 0.62f, color = glowA.copy(alpha = alphaA))
            glow(center = Offset(0.78f, 0.82f), radius = 0.58f, color = glowB.copy(alpha = alphaB))
        }
    }
}

// ---- 兜底色（拿不到封面时用）：分深浅两套 ------------------------------------

/** #50514f 中性暖灰（深色兜底）。原版曾是发蓝的 #45516c，用户 m01026 说蓝色不符合软件。 */
private val FALLBACK_A = Color(0xFF50514F)

/** #455538 olive（深色兜底）。 */
private val FALLBACK_B = Color(0xFF455538)

/** #b1b1af 中性暖灰（浅色兜底，同色相提亮）。 */
private val FALLBACK_A_LIGHT = Color(0xFFB1B1AF)

/** #b7c69c（浅色兜底）。 */
private val FALLBACK_B_LIGHT = Color(0xFFB7C69C)

private const val GLOW_ALPHA_A = 0.35f
private const val GLOW_ALPHA_B = 0.42f
private const val GLOW_ALPHA_A_LIGHT = 0.55f
private const val GLOW_ALPHA_B_LIGHT = 0.60f

/**
 * 在**归一化坐标**里画一团径向衰减的柔光。
 *
 * `scale` 把单位正方形拉成整屏，于是正圆变成椭圆 —— 这正是原版
 * `GradientTexture2D`（512×512 正圆）+ `STRETCH_SCALE` 拉满整屏的效果。
 */
private fun DrawScope.glow(center: Offset, radius: Float, color: Color) {
    scale(scaleX = size.width, scaleY = size.height, pivot = Offset.Zero) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color, Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

/**
 * 取一张图的平均色（原版 `set_from_image`：先缩到 4×4 再逐像素平均）。
 *
 * 封面是远程 URL，交给 Coil 加载 —— 它在内存/磁盘缓存里与界面上的 `AsyncImage` 同一份，
 * 不会为此多下一趟网。`allowHardware(false)` 是因为要逐像素读。
 */
private suspend fun averageColor(context: Context, model: Any): Color? {
    val request = ImageRequest.Builder(context)
        .data(model)
        .size(4, 4)
        .allowHardware(false)
        .build()
    val drawable = runCatching { context.imageLoader.execute(request).drawable }.getOrNull() ?: return null
    val bitmap = drawable.toBitmapOrNull() ?: return null
    return averageOfBitmap(bitmap)
}

/** 自定义背景已经是 Bitmap（`CustomArt.background` 是个 StateFlow<Bitmap?>），直接取样。 */
private fun averageOfBitmap(bitmap: android.graphics.Bitmap): Color? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null

    var r = 0L
    var g = 0L
    var b = 0L
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            val px = bitmap.getPixel(x, y)
            r += (px shr 16) and 0xFF
            g += (px shr 8) and 0xFF
            b += px and 0xFF
        }
    }
    val n = (bitmap.width * bitmap.height).toFloat()
    return Color(
        red = r / n / 255f,
        green = g / n / 255f,
        blue = b / n / 255f,
    )
}
