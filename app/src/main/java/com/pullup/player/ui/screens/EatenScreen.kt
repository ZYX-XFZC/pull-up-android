package com.pullup.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo

/** 「页面被吃了」的原文案（`EatenScreen.gd:16` 的 `const TEXT`）。探针按这句话定位这一页，一个字都不能改。 */
private const val EATEN_TEXT = "页面被吃了(╥﹏╥)"

/**
 * 「页面被吃了(╥﹏╥)」整页，移植自 `scripts/ui/EatenScreen.gd`。
 *
 * 原版这一页**整棵子树里只有一个 Label**（`EatenScreen.gd:29-51`）：
 * `Backdrop`(色 `BG`) → `CenterContainer` → `EatenText`。它**不 extends SubScreen**，
 * 所以没有返回圆钮、没有标题、没有滚动区 —— 这里也刻意不套二级页骨架：
 * 多一个空壳顶栏，「整页只剩一句话」的效果就没了。
 *
 * 三条原版事实，逐条照抄：
 * - 文字 `页面被吃了(╥﹏╥)`、字号 `FONT_TITLE`（→ `Typo.TITLE`）、色 `TEXT`、
 *   水平与垂直居中（`EatenScreen.gd:45-49`）。
 * - 背景是不透明的 `AppTheme.BG`（`EatenScreen.gd:32`）：它盖在最上面，身后没有可依赖的底。
 * - 点整页退出，而且**只认「按下」**：原版 `_gui_input` 在 `pressed` 为假时直接 return
 *   （`EatenScreen.gd:54-64`），注释写明理由是「输密码那一下的**松开**不能顺手把这一页点没了」。
 *   所以这里用 `detectTapGestures(onPress = ...)`（按下即触发）而不是 `clickable`
 *   （后者要等抬起才回调，等于把原版特意挡掉的那个时机又放回来了）。
 */
@Composable
fun EatenScreen(onClose: () -> Unit) {
    val c = PullTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            // 只认按下：抬起不触发（等价原版 `accept_event()` 后 `close_requested.emit()`）。
            .pointerInput(Unit) {
                detectTapGestures(onPress = { onClose() })
            },
        // 等价原版 CenterContainer：文字在整屏正中间。
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = EATEN_TEXT,
            color = c.text,
            fontSize = Typo.TITLE.sp,
            // 等价原版 `HORIZONTAL_ALIGNMENT_CENTER`（原版没有左右内边距，这里也不加）。
            textAlign = TextAlign.Center,
        )
    }
}
