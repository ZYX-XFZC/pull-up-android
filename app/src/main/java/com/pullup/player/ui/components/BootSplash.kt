package com.pullup.player.ui.components

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInQuint
import androidx.compose.animation.core.EaseOutQuint
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import kotlinx.coroutines.delay

/**
 * 细线尺寸（原版 `BootSplash.gd:13-14` `BAR_WIDTH := 420.0` / `BAR_HEIGHT := 6.0`，
 * 画布 1080px = 432dp ⇒ ÷2.5）。
 */
private const val BAR_WIDTH_DP = 168f
private const val BAR_HEIGHT_DP = 2.4f

/** 品牌块内间距（原版 `:52` separation 18 ⇒ 7.2dp）。 */
private const val BRAND_GAP_DP = 7.2f

/**
 * 各段时长，会乘上用户的动效速度。
 *
 * 原版走 `AppTheme.anim(base) = base / motion_scale`（`AppTheme.gd:105`），
 * Kotlin 等价物是 `PullThemeState.anim(base)`（`Theme.kt:179`）。
 */
private const val FADE_IN = 0.24f
private const val SWEEP = 0.46f
private const val HOLD = 0.08f
private const val FADE_OUT = 0.32f
private const val SKIP_FADE = 0.14f

/**
 * 启动初始化动画（原版 `scripts/ui/BootSplash.gd`，用户 m03569）。
 *
 * 开机头几帧要建 UI、预热字体，界面会先「半成品」闪一下；这层把它盖住，顺带给一次有节奏的
 * 入场：品牌淡入 + 一条细线扫满，然后整层淡出并自毁。点一下屏幕（或按任意键）可以跳过。
 *
 * **硬约束：不做呼吸类循环动画**（原版 `:7`）。
 *
 * 两处与原版一致的细节，别顺手「优化」掉：
 * - 整层一开始就完全不透明（`:34` `modulate.a = 1.0`）。要淡的是**品牌块**，不是整层 ——
 *   否则淡入期间底下那页的标题会透出来，看起来像「多了一个标题」（用户 m01636）。
 * - 细线的底槽用 `LINE`、进度用 `ACCENT`，两者都在同一个 168×2.4dp 的盒子里左对齐生长。
 *
 * @param onDone 整层淡出结束（或跳过淡出结束）后回调一次，调用方据此把它从树里摘掉。
 */
@Composable
fun BootSplash(onDone: () -> Unit) {
    val c = PullTheme.colors
    val theme = PullTheme.state

    val brandAlpha = remember { Animatable(0f) }
    val fillFraction = remember { Animatable(0f) }
    val layerAlpha = remember { Animatable(1f) }
    var skipping by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // 用 `skipping` 当 key：它一变，上一轮的协程就被取消（等价原版 `_skip()` 里的 `_tween.kill()`），
    // 于是不会出现「跳过之后原动画还在后面接着跑」。
    LaunchedEffect(skipping) {
        if (skipping) {
            layerAlpha.animateTo(0f, tween(theme.anim(SKIP_FADE), easing = EaseInQuint))
        } else {
            brandAlpha.animateTo(1f, tween(theme.anim(FADE_IN), easing = EaseOutQuint))
            fillFraction.animateTo(1f, tween(theme.anim(SWEEP), easing = EaseOutQuint))
            delay(theme.anim(HOLD).toLong())
            layerAlpha.animateTo(0f, tween(theme.anim(FADE_OUT), easing = EaseInQuint))
        }
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(layerAlpha.value)
            .background(c.bg)
            .focusRequester(focusRequester)
            .focusable()
            // 按任意键跳过（原版 `_unhandled_key_input`）。
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    skipping = true
                    true
                } else {
                    false
                }
            }
            // 点一下跳过（原版 `_gui_input`）。这一层同时要吃掉触摸，别让点击穿到底下的页面。
            .pointerInput(Unit) { detectTapGestures { skipping = true } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.alpha(brandAlpha.value),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BRAND_GAP_DP.dp),
        ) {
            Text(text = "♪", color = c.accent, fontSize = Typo.DISPLAY.sp)
            Text(text = "Pull Up", color = c.text, fontSize = Typo.TITLE.sp)
            Text(
                text = "本地优先 · 数据只在你手机上",
                color = c.textDim,
                fontSize = Typo.META.sp,
                textAlign = TextAlign.Center,
            )
            Box(Modifier.width(BAR_WIDTH_DP.dp).height(BAR_HEIGHT_DP.dp)) {
                Box(Modifier.fillMaxSize().background(c.line))
                Box(Modifier.fillMaxWidth(fillFraction.value).fillMaxHeight().background(c.accent))
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/**
 * 启动动画的进程级开关（原版 `main.gd:44` 的 `static var boot_shown`）。
 *
 * 必须是进程级而不是 `remember`：切换深色模式会重建整棵界面树，`remember` 会被丢掉、
 * 动画重播一次。原版 `_should_show_boot()` 头一行就是 `if boot_shown: return false`，
 * 开发探针 `BootProbe.gd:82` 正是断言「同一次进程不会再播启动动画」。
 */
object BootSplashState {

    private var shown = false

    /**
     * 该不该播这一次。
     *
     * 与原版 `_should_show_boot()`（`main.gd:163-170`）逐条对应：本进程已播过 → 不播；
     * 用户在「外观设置 → 启动动画」里关掉 → 不播；无头环境 → 不播。
     *
     * @param animationEnabled 取 `Preferences.bootAnimation()`。
     */
    fun shouldShow(animationEnabled: Boolean): Boolean {
        if (shown) return false
        if (!animationEnabled) return false
        if (isHeadless) return false
        shown = true
        return true
    }

    /**
     * 原版判据是 `DisplayServer.get_name() == "headless"`；本工程里「无头」就是 Robolectric
     * 单元测试（没有真实帧时钟与屏幕），此时播动画只会拖慢测试并让界面断言多出一层遮挡。
     */
    private val isHeadless: Boolean
        get() = Build.FINGERPRINT.contains("robolectric", ignoreCase = true)
}
