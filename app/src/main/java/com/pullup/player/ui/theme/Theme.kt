package com.pullup.player.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pullup.player.R

/**
 * 视觉系统单一真源，移植自 Godot 版 scripts/ui/AppTheme.gd。
 *
 * 设计画布 1080x1920（竖屏），原稿所有数值都按这个尺度写。Android 上我们按 dp 重建：
 * 1080px 宽 = 412dp，即 1dp ≈ 2.62px，故原稿 px 值 ÷ 2.62 得到 dp。
 *
 * 调色板沿用原型：近黑蓝画布 + 青柠强调色 + 卡片发丝描边 + 胶囊按钮 + 逐曲环境光晕。
 * 面板材质有「亚克力 / 云母 / 纯色」三档，深浅模式两套令牌。
 */

// ---- 模式 ----------------------------------------------------------------

const val MODE_DARK = 0
const val MODE_LIGHT = 1

val MODE_NAMES = listOf("深色", "浅色")

/** 面板质感：0 = 亚克力（最透）1 = 云母（中等）2 = 纯色（完全不透明）。 */
const val PANEL_ACRYLIC = 0
const val PANEL_MICA = 1
const val PANEL_SOLID = 2

val PANEL_STYLE_NAMES = listOf("亚克力", "云母", "纯色")

/** 深色模式下的「白纱」浓度（亚克力 / 云母）。 */
private val PANEL_ALPHAS = listOf(0.065f, 0.105f)

/** 浅色模式下的「白纱」浓度（浅底上要厚一点才看得出是块面板）。 */
private val PANEL_ALPHAS_LIGHT = listOf(0.55f, 0.78f)

/**
 * 令牌表：所有颜色都由 (模式, 质感) 决定。等价于 AppTheme.refresh_tokens()。
 */
data class PullColors(
    val bg: Color,
    val surface: Color,
    val surfaceHi: Color,
    val card: Color,
    val line: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val accentHi: Color,
    val accentDim: Color,
    val accent2: Color,
    val violet: Color,
    val onAccent: Color,
    val icon: Color,
    val track: Color,
    val scrim: Color,
    val shade: Color,
)

private fun colorsFor(mode: Int, panelStyle: Int): PullColors {
    val light = mode == MODE_LIGHT
    val solid = panelStyle >= PANEL_SOLID

    val surface: Color
    val surfaceHi: Color
    val card: Color
    if (solid) {
        if (light) {
            surface = Color(1f, 1f, 1f)
            surfaceHi = Color(1f, 1f, 1f)
            card = Color(1f, 1f, 1f)
        } else {
            // #151619 中性灰，不蓝。
            val s = Color(0.0823529f, 0.0862745f, 0.0980392f)
            surface = s
            surfaceHi = s.lighten(0.07f)
            card = s.lighten(0.03f)
        }
    } else if (light) {
        val a = PANEL_ALPHAS_LIGHT[panelStyle.coerceIn(0, 1)]
        surface = Color(1f, 1f, 1f, a)
        surfaceHi = Color(1f, 1f, 1f, minOf(a + 0.18f, 1f))
        card = Color(1f, 1f, 1f, maxOf(a - 0.10f, 0f))
    } else {
        val a = PANEL_ALPHAS[panelStyle.coerceIn(0, 1)]
        surface = Color(1f, 1f, 1f, a)
        surfaceHi = Color(1f, 1f, 1f, minOf(a + 0.055f, 1f))
        card = Color(1f, 1f, 1f, maxOf(a - 0.02f, 0.001f))
    }

    val accent: Color
    val accentHi: Color
    val onAccent: Color
    if (light) {
        // #679818 浅底上的 lime 要压深，否则看不清。
        accent = Color(0.4039216f, 0.5960784f, 0.1098039f)
        accentHi = Color(0.4705882f, 0.6745098f, 0.1411765f)
        onAccent = Color.White
    } else {
        accent = Color(0.7372549f, 0.9215686f, 0.4117647f) // #bceb69 lima
        accentHi = Color(0.8313726f, 0.9647059f, 0.5411765f)
        onAccent = Color(0.0313726f, 0.0745098f, 0.1372549f)
    }

    return PullColors(
        bg = if (light) Color(0.9294118f, 0.9372549f, 0.9490196f) else Color(0.0509804f, 0.0588235f, 0.0745098f),
        surface = surface,
        surfaceHi = surfaceHi,
        card = card,
        line = if (light) Color(0f, 0f, 0f, 0.10f) else Color(1f, 1f, 1f, 0.10f),
        text = if (light) Color(0.0784314f, 0.0862745f, 0.1019608f) else Color(0.9490196f, 0.9568627f, 0.9647059f),
        textDim = if (light) Color(0.3568628f, 0.3803922f, 0.4274510f) else Color(0.6039216f, 0.6392157f, 0.6862745f),
        accent = accent,
        accentHi = accentHi,
        accentDim = Color(accent.red, accent.green, accent.blue, 0.18f),
        accent2 = if (light) Color(0.2784314f, 0.3098039f, 0.3529412f) else Color(0.8784314f, 0.8901961f, 0.9058824f),
        violet = if (light) Color(0.4549019f, 0.4392157f, 0.4039216f) else Color(0.6901961f, 0.6705882f, 0.6274510f),
        onAccent = onAccent,
        icon = if (light) Color(0.0784314f, 0.0862745f, 0.1019608f, 0.86f) else Color(1f, 1f, 1f, 0.92f),
        track = if (light) Color(0f, 0f, 0f, 0.12f) else Color(1f, 1f, 1f, 0.14f),
        scrim = if (light) Color(0f, 0f, 0f, 0.32f) else Color(0f, 0f, 0f, 0.42f),
        shade = if (light) Color(0f, 0f, 0f, 0.10f) else Color(0f, 0f, 0f, 0.28f),
    )
}

private fun Color.lighten(amount: Float): Color = Color(
    red = (red + (1f - red) * amount).coerceIn(0f, 1f),
    green = (green + (1f - green) * amount).coerceIn(0f, 1f),
    blue = (blue + (1f - blue) * amount).coerceIn(0f, 1f),
    alpha = alpha,
)

/**
 * 主题运行时状态。持有当前模式与质感，任何一处改动都会整体重算令牌，
 * Compose 侧直接重组（等价于 Godot 版「就地换色、不重建场景」）。
 */
class PullThemeState(mode: Int = MODE_DARK, panelStyle: Int = PANEL_ACRYLIC, motionScale: Float = 1f) {
    var mode by mutableIntStateOf(mode)
        private set
    var panelStyle by mutableIntStateOf(panelStyle)
        private set
    var motionScale by mutableStateOf(motionScale)
        private set

    /** 令牌表。读它就会订阅模式/质感变化。 */
    val colors: PullColors
        get() = colorsFor(mode, panelStyle)

    val isDark: Boolean get() = mode == MODE_DARK
    val modeName: String get() = MODE_NAMES[mode]
    val panelStyleName: String get() = PANEL_STYLE_NAMES[panelStyle]

    // 注意：不能用 setMode/setPanelStyle/setMotionScale 命名 —— `var x by mutableStateOf`
    // 已经生成了 setX 的 JVM 签名，同名的 fun 会撞成 Platform declaration clash。
    fun applyMode(next: Int) {
        mode = next.coerceIn(0, MODE_NAMES.lastIndex)
    }

    fun applyPanelStyle(next: Int) {
        panelStyle = next.coerceIn(0, PANEL_STYLE_NAMES.lastIndex)
    }

    fun applyMotionScale(next: Float) {
        motionScale = next.coerceIn(0.25f, 2f)
    }

    /** 把设计时长换算成当前倍率下的实际时长（倍率越大越快）。 */
    fun anim(baseSeconds: Float): Int = ((baseSeconds / motionScale) * 1000f).toInt().coerceAtLeast(1)
}

val LocalPullColors = staticCompositionLocalOf { colorsFor(MODE_DARK, PANEL_ACRYLIC) }
val LocalPullTheme = staticCompositionLocalOf<PullThemeState> { error("PullTheme 未提供") }

/** 便捷取色：`PullTheme.colors.accent`。 */
object PullTheme {
    val colors: PullColors
        @Composable get() = LocalPullColors.current

    val state: PullThemeState
        @Composable get() = LocalPullTheme.current
}

// ---- 几何（原稿 px ÷ 2.62 → dp） -----------------------------------------

object Dims {
    /** 屏幕左右留白。原稿 PAD_SCREEN = 40px。 */
    const val PAD_SCREEN = 16

    /** 卡片圆角（18px @2.5x）。 */
    const val RADIUS = 18

    /** 内部小格子圆角。 */
    const val RADIUS_SMALL = 13

    /** 胶囊。 */
    const val RADIUS_PILL = 999
}

/** 圆角速写：`pullShape(Dims.RADIUS)`。 */
fun pullShape(radius: Int) = RoundedCornerShape(radius.dp)

// ---- 字号（原稿 px ÷ 2.5 → sp） -------------------------------------------

object Typo {
    /** 首页问候语 / 播放页歌名。原稿 64px。 */
    const val DISPLAY = 26
    /** 二级页标题。原稿 50px。 */
    const val TITLE = 20
    /** 播放页歌词行、正文。原稿 42px。 */
    const val BODY = 17
    /** 按钮、设置行标题。原稿 32px。 */
    const val SUB = 13
    /** 副标题、时间戳。原稿 28px。 */
    const val META = 11
    /** 全大写小标（YOUR SOUND, YOUR SPACE）。原稿 24px。 */
    const val EYEBROW = 10
}

// ---- 动效时长（秒，原稿常量） ---------------------------------------------

object Motion {
    const val ANIM_AMBIENT = 0.65f
    const val ANIM_VIEW_IN = 0.30f
    const val ANIM_VIEW_OUT = 0.20f
    const val SLIDE_PAGE = 20f
    const val SLIDE_PLAYER = 40f
    const val ANIM_SONG_TITLE = 0.40f
    const val ANIM_SONG_ARTIST = 0.55f
    const val ANIM_SONG_COVER = 0.45f
    const val ANIM_BREATH = 2.6f
    const val PRESS_SCALE = 0.88f
}

// ---- 字体 ----------------------------------------------------------------

/**
 * 梦源黑体 CN W19（usWeightClass 666，中偏粗）——深色背景上偏粗更清晰。
 * 随包走，因为 Android 系统字体不保证有这一款。
 */
val DreamHanSans: FontFamily = FontFamily(
    Font(R.font.dream_han_sans_cn_w19, FontWeight.Normal),
    Font(R.font.dream_han_sans_cn_w19, FontWeight.Medium),
    Font(R.font.dream_han_sans_cn_w19, FontWeight.SemiBold),
    Font(R.font.dream_han_sans_cn_w19, FontWeight.Bold),
)
