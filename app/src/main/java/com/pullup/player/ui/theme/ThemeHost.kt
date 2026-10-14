package com.pullup.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.pullup.player.data.Preferences

/**
 * 把 [Preferences] 里的外观设置接到 Compose 主题上。
 *
 * 等价于 Godot 版的「就地换色」：设置一变就整体重算令牌并重组，**不重建 Activity**。
 * 令牌表按 (模式, 质感) 纯函数算出，因此这里只是一个订阅桥。
 */
@Composable
fun PullUpTheme(prefs: Preferences, content: @Composable () -> Unit) {
    val state = remember {
        PullThemeState(
            mode = if (prefs.darkMode()) MODE_DARK else MODE_LIGHT,
            panelStyle = prefs.panelStyle(),
            motionScale = prefs.motionScale(),
        )
    }

    LaunchedEffect(prefs) {
        prefs.changed.collect {
            state.applyMode(if (prefs.darkMode()) MODE_DARK else MODE_LIGHT)
            state.applyPanelStyle(prefs.panelStyle())
            state.applyMotionScale(prefs.motionScale())
        }
    }

    // 读一次 colors 就订阅了 mode/panelStyle，两者一变本函数重组并重新下发。
    val colors = state.colors
    CompositionLocalProvider(
        LocalPullTheme provides state,
        LocalPullColors provides colors,
    ) {
        MaterialTheme(
            content = {
                // 全局默认字体：梦源黑体 CN W19。
                // 原版是 `theme.default_font = font`（`AppTheme.gd:114`，字体文件
                // `assets/fonts/DreamHanSansCN-W19.ttf`，usWeightClass 666 中偏粗），
                // 即**整个 App 的默认字体**，不是某几处的点缀。这里在 MaterialTheme
                // 之内再下发一层：继承它给的 bodyLarge 字号，只把字体族换掉。
                ProvideTextStyle(
                    value = LocalTextStyle.current.copy(fontFamily = DreamHanSans),
                    content = content,
                )
            },
        )
    }
}

/** 未接设置时的兜底主题（预览 / 测试用）。 */
@Composable
fun PullUpThemePreview(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalPullColors provides previewColors(dark),
    ) {
        MaterialTheme(
            content = {
                ProvideTextStyle(
                    value = LocalTextStyle.current.copy(fontFamily = DreamHanSans),
                    content = content,
                )
            },
        )
    }
}

private fun previewColors(dark: Boolean) = PullColors(
    bg = if (dark) androidx.compose.ui.graphics.Color(0xFF0D0F13) else androidx.compose.ui.graphics.Color(0xFFECEFF2),
    surface = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.065f),
    surfaceHi = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f),
    card = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.045f),
    line = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f),
    text = if (dark) androidx.compose.ui.graphics.Color(0xFFF2F4F6) else androidx.compose.ui.graphics.Color(0xFF141619),
    textDim = if (dark) androidx.compose.ui.graphics.Color(0xFF9AA3AF) else androidx.compose.ui.graphics.Color(0xFF5B616D),
    accent = androidx.compose.ui.graphics.Color(0xFFBCEB69),
    accentHi = androidx.compose.ui.graphics.Color(0xFFD4F68A),
    accentDim = androidx.compose.ui.graphics.Color(0x2EBCEB69),
    accent2 = androidx.compose.ui.graphics.Color(0xFFE0E3E7),
    violet = androidx.compose.ui.graphics.Color(0xFFB0ABA0),
    onAccent = androidx.compose.ui.graphics.Color(0xFF080A10),
    icon = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f),
    track = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f),
    scrim = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.42f),
    shade = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.28f),
)
