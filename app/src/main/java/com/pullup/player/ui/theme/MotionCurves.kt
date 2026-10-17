package com.pullup.player.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import kotlin.math.exp

/**
 * 动效曲线，移植自原版 `scripts/ui/Motion.gd`（配方来自 `docs/REF_RHINE.md`）。
 *
 * ★ 这个文件里**没有** `Motion` 这个名字的对象 —— 时长/缩放那些设计令牌仍留在
 * `Theme.kt` 的 `object Motion` 里（`ANIM_VIEW_IN` / `PRESS_SCALE` 等）。这里只放
 * **曲线与节奏**，避免两个同名的东西。
 *
 * ## 原版六组能力，这里只搬两组（其余在原版里是死代码）
 *
 * 已用 grep 核过 `Motion.*` 在原版的全部调用点，共 23 处：
 *
 * | 能力 | 原版调用点 | 这里 |
 * |---|---|---|
 * | `damp()` 临界阻尼弹簧 | `ui/PlayerPage.gd:272`（进度条跟随，`rate = 14.0`） | ✅ [dampEasing] |
 * | `stagger_in()` 错峰入场 | `ui/ActionSheet.gd:102,234,330,349,360,370` | ✅ [Stagger] |
 * | `fade_in()` / `fill_progressive()` / `cancel_fill()` | 各列表页铺行（Album/Artist/Playlist/Queue/Search） | ❌ 见下 |
 * | `quintic_coefficients()` / `quintic_sample()` | **零命中**（写了没用上） | ❌ 不搬 |
 * | `interrupted_duration()` | **零命中**（写了没用上） | ❌ 不搬 |
 *
 * 后两组在原版没有任何调用点，搬过来只是多两个没人调的函数。
 * `fill_progressive` 那三件套在 Kotlin 侧也不需要：原版是手写 `VBoxContainer` 才要
 * 自己分批铺（`Motion.gd:118-121` 注释记录「100 条一次性 add_child，那一帧 134ms」），
 * Compose 的 `LazyColumn` 本来就只组合可见项，分批是白送的。
 */

// ---- 临界阻尼弹簧 ---------------------------------------------------------

/**
 * 临界阻尼阶跃响应的解析形状 —— 原版 `Motion.damp()`（`Motion.gd:51-58`）的连续形式。
 *
 * 原版是**每帧推进一次**的离散递推：
 * ```
 * offset  = value - target
 * decay   = exp(-rate * delta)
 * advance = (velocity + rate * offset) * delta
 * 新值     = target + (offset + advance) * decay
 * 新速度   = (velocity - rate * advance) * decay
 * ```
 * 从「静止在 0、目标 1」出发，这条递推的解就是经典临界阻尼阶跃响应：
 * ```
 * f(x) = 1 - (1 + x)·e^(-x)     （x = ωt）
 * ```
 * Compose 的 `animateFloatAsState` 只接受 `Easing`（纯函数，时间进度 → 数值进度），
 * 所以这里把上面这条曲线按总时长归一化后直接用 —— **形状与原版逐点一致**，
 * 又不需要开一个永不收敛的 `withFrameNanos` 循环
 * （那种循环会让 Compose 永远不进入 idle，Robolectric 测试会直接超时，
 * 本项目 `ShellSmokeTest` 就踩过这个坑）。
 *
 * 为什么不继续用原来的 `LinearEasing`：线性是匀速追赶，播放位置每 250ms 才来一次，
 * 看起来就是「一格一格匀速滑过去」；原版弹簧起步快、末段指数收敛，手感不同。
 *
 * @param rate 收敛速度，原版进度条跟随用 `14.0`（`PlayerPage.gd:272`）。
 * @param durationMs 这次动画的总时长；曲线在这一段里走完全程。
 */
fun dampEasing(rate: Float, durationMs: Int): Easing {
    // span = ωT：整个时长里经历了多少个「时间常数」。rate = 14、T = 250ms ⇒ 3.5。
    val span = rate * (durationMs / 1000f)
    // 走到 T 时还剩多少没走完 —— 用它把曲线上端归一到 1，
    // 否则 dt 内收敛不完，动画结束时数值会「跳」一下到目标。
    val remainder = 1f - dampShape(span)
    val inverse = if (remainder <= 0.0001f) 1f else 1f / remainder
    return Easing { fraction ->
        (dampShape(span * fraction) * inverse).coerceIn(0f, 1f)
    }
}

/** 临界阻尼阶跃响应 `1 - (1 + x)·e^(-x)`，其中 x = ωt。 */
private fun dampShape(x: Float): Float {
    if (x <= 0f) return 0f
    return 1f - (1f + x) * exp(-x)
}

// ---- 错峰入场 -------------------------------------------------------------

/**
 * 逐项错峰入场的参数，对应原版 `Motion.stagger_in(root, step, duration)`
 * （`Motion.gd:76-97`）。
 *
 * 原版做三件事：第 i 项延迟 `i * step`、单项时长 `duration`、把 `scale` 从 0.97 拉到 1
 * 同时把 `modulate.a` 从 0 拉到 1，曲线 `TRANS_QUINT` + `EASE_OUT`
 * （Kotlin 侧用 `FastOutSlowInEasing` 近似 easeOutQuint，与项目里其他页面一致）。
 *
 * 两条照抄的规矩：
 * - **只给前 [MAX_ITEMS] 项做入场**（`Motion.gd:84-88`）：更靠后的项本来就在屏外，
 *   各建一个动画只是白烧一帧（原版注释记录「实测 100 项 ≈ 上百个 Tween」）。
 * - **只动透明度与缩放，不动位置**（`Motion.gd:69-71`）：父级是容器时每帧都会重排
 *   子节点位置，动位置会被立刻覆盖；Compose 里同理，动 `translationY` 会跟父布局抢控制权。
 *
 * 用法是在面板里对第 i 行取 `delayMillis = i * STEP_MS`、时长取 `DURATION_MS`。
 */
object Stagger {
    /** 原版默认步进（`Motion.gd:76` 的 `step = 0.026`）：26ms。 */
    const val STEP_MS = 26L

    /** 原版默认单项时长（`Motion.gd:76` 的 `duration = 0.30`）：300ms。 */
    const val DURATION_MS = 300

    /** 只给前多少项做入场（`Motion.gd:15 MAX_STAGGER_ITEMS := 24`）。 */
    const val MAX_ITEMS = 24

    /** 操作面板**主菜单**的步进（`ActionSheet.gd:102`：`stagger_in(_actions, 0.03, 0.24)`）。 */
    const val SHEET_STEP_MS = 30L

    /** 主菜单单项时长（`ActionSheet.gd:102`，`duration = 0.24`）。 */
    const val SHEET_DURATION_MS = 240

    /** **二级面板**单项时长（`ActionSheet.gd:234,330,349,360,370`：`0.03, 0.18`）。 */
    const val SUB_SHEET_DURATION_MS = 180

    /** 入场起点的缩放（`Motion.gd:92` 的 `Vector2(0.97, 0.97)`）。 */
    const val FROM_SCALE = 0.97f

    /**
     * 第 [index] 项该用多少毫秒延迟；超出 [MAX_ITEMS] 的项直接返回 0
     * （原版把这些项设成「立刻可见」，这里对应「不延迟、时长为 0」）。
     */
    fun delayFor(index: Int, stepMs: Long): Long = if (index >= MAX_ITEMS) 0L else index * stepMs

    /**
     * 第 [index] 项该用多长的动画；超出 [MAX_ITEMS] 的项返回 0（立刻可见）。
     */
    fun durationFor(index: Int, durationMs: Int): Int = if (index >= MAX_ITEMS) 0 else durationMs
}

// ---- 按压反馈 -------------------------------------------------------------

/** 按下时长基准 `AppTheme.PRESS_IN = 0.09` 秒（`AppTheme.gd:96`）。 */
private const val PRESS_IN_SECONDS = 0.09f

/** 松开时长基准 `AppTheme.PRESS_OUT = 0.18` 秒（`AppTheme.gd:97`）。 */
private const val PRESS_OUT_SECONDS = 0.18f

/** `TRANS_QUAD + EASE_OUT` 的等价三次贝塞尔（按下，先快后慢）。 */
private val PRESS_IN_EASING = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)

/** `TRANS_BACK + EASE_OUT` 的等价三次贝塞尔（松开，会先冲过头一点再落回）。 */
private val PRESS_OUT_EASING = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/**
 * 「按下去缩一点」的缩放值，移植自 `scripts/ui/TransportButton.gd:41-57`。
 *
 * 原版两个方向都走 `_animate_scale()`：
 * - 按下 ⇒ `AppTheme.PRESS_SCALE`（`AppTheme.gd:95` = **0.88**）、`PRESS_IN`(0.09s)、
 *   `Tween.TRANS_QUAD` + `Tween.EASE_OUT`；
 * - 松开 ⇒ 回 `1.0`、`PRESS_OUT`(0.18s)、`Tween.TRANS_BACK` + `Tween.EASE_OUT`
 *   （`TRANS_BACK` 会过冲，所以松手时有一点「弹回来」的手感）。
 *
 * 时长过 `PullThemeState.anim()`，和原版 `AppTheme.anim(duration)` 一样随
 * 「动效速度」设置伸缩，而不是写死 90/180ms。
 *
 * 注意**只用于传输类按钮**（`TransportButton` 的子类实例）。原版另有
 * `UIFactory.gd:24 add_press_feedback(button, depth = 0.965)` 是给普通行/卡片用的
 * 浅缩放，数值不同，不要混用。
 *
 * 用法：
 * ```
 * val interaction = remember { MutableInteractionSource() }
 * val scale = rememberPressScale(interaction)
 * Box(Modifier.scale(scale).clickable(interactionSource = interaction, indication = null, ...))
 * ```
 */
@Composable
fun rememberPressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val theme = PullTheme.state
    return animateFloatAsState(
        targetValue = if (pressed) Motion.PRESS_SCALE else 1f,
        animationSpec = tween(
            durationMillis = theme.anim(if (pressed) PRESS_IN_SECONDS else PRESS_OUT_SECONDS),
            easing = if (pressed) PRESS_IN_EASING else PRESS_OUT_EASING,
        ),
        label = "pressScale",
    ).value
}
