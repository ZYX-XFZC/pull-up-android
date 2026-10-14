package com.pullup.player.ui.screens

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.data.TextGuard
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.components.RoundIconButton
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.Motion
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.pullShape

// ---------------------------------------------------------------------------
// 与 `PasscodeScreen.gd` 对齐的常量（本包内两处 PIN 界面共用）
//
// 换算口径（`scripts/ui/AppTheme.gd:63` 逐字）：「我们的画布 1080px = 432dp，
// 即 1dp = 2.5px」⇒ **Godot 的 px ÷ 2.5 = Compose 的 dp**。
// ---------------------------------------------------------------------------

/** `MIN_LEN := 4`（`PasscodeScreen.gd:20`）。 */
internal const val PASSCODE_MIN_LEN = 4

/** `MAX_LEN := 6`（`PasscodeScreen.gd:21`）。 */
internal const val PASSCODE_MAX_LEN = 6

/**
 * `KEY_HEIGHT := 140.0`（`PasscodeScreen.gd:23`）→ 140 ÷ 2.5 = **56dp**。
 * 原注释：数字键的触控下限 120px 放松一格。
 */
internal const val PASSCODE_KEY_HEIGHT_DP = 56

/** 圆点槽边长 `custom_minimum_size = Vector2(44, 44)`（`PasscodeScreen.gd:49`）→ 44 ÷ 2.5 = 17.6dp。 */
private const val DOT_SIZE_DP = 17.6

/** 圆点间距 `dots separation = 24`（`PasscodeScreen.gd:43`）→ 24 ÷ 2.5 = 9.6dp。 */
private const val DOT_GAP_DP = 9.6

/** 键盘键距 `h_separation / v_separation = 24`（`PasscodeScreen.gd:66-67`）→ 24 ÷ 2.5 = 9.6dp。 */
private const val KEY_GAP_DP = 9.6

/**
 * 二级页骨架的间距（`SubScreen.gd`，px ÷ 2.5）：
 * `Margin.margin_top = 64`（`:134`）→ 25.6dp、`Column.separation = 22`（`:140`）→ 8.8dp、
 * `content.separation = 12`（`:182`）→ 4.8dp、`TopBar.separation = 20`（`:145`）→ 8dp；
 * 输入框/按钮的 `custom_minimum_size.y = 120`（`PasswordScreen.gd:29,38`）→ 48dp。
 */
private const val TOP_GAP_DP = 25.6
private const val BLOCK_GAP_DP = 8.8
private const val ROW_GAP_DP = 4.8
private const val TOPBAR_GAP_DP = 8.0
private const val FIELD_HEIGHT_DP = 48

/**
 * 输入框左右内边距：`AppTheme.field_box` 的 LineEdit 皮肤给 `content_margin_left/right = 34`
 * （`AppTheme.gd:655-656,662-663`）→ 13.6dp；上下 `20`（`:657-658,664-665`）→ 8dp。
 */
private const val FIELD_PAD_X_DP = 13.6
private const val FIELD_PAD_Y_DP = 8.0

/** `PasswordField.max_length = 32`（`PasswordScreen.gd:28`）：歌单密码是任意文本，不是 PIN。 */
private const val PASSWORD_MAX = 32

/**
 * 私密歌单密码页，移植自 `scripts/ui/PasswordScreen.gd`。
 *
 * 三条原版事实，全部照抄：
 * - 这一页的输入控件是**一个 `LineEdit` 文本框**（`secret = true`、占位「输入歌单密码」、
 *   最长 32 字、回车即提交），**不是** `PasscodeScreen` 的数字键盘 —— 歌单密码是任意
 *   字符串（`Playlists` 只存 sha256 摘要），键盘页是给「设置密码」用的另一页（2.15）。
 * - 标题是**歌单名**（过 `TextGuard.mask`，缺省「私密歌单」）；副标题固定
 *   「这个歌单被设为私密，输入密码才能进入」（`PasswordScreen.gd:21-22`）。
 * - **输错一次就直接跳到「页面被吃了」**：原版 `_submit` 的 else 分支写「密码不对」后
 *   立刻 `close()` 并 `rejected.emit(id)`（`:134-136`），首页收到便打开 `EatenScreen`
 *   （错误文案只在页面存续的瞬间可见）。原版**没有任何连错计数**，所以这里也没有。
 *
 * 校验走 `Playlists.unlock(id, text)` 一次（它内部先 `verify` 比对 sha256 摘要、再记下
 * 本次会话的解锁 id）；`unlock` 对**没有密码的歌单**返回 true —— 这个页面被误开时不会把人锁死。
 *
 * 上下两个等重空块＝原版 `TopPad`/`BottomPad`（`PasswordScreen.gd:60-71`）：内容在视口里垂直居中
 * （用户 m01007 抱怨过密码页太靠上）。Godot 那套「等两帧再按视口高算垫块」（`:72-108`）是
 * `ScrollContainer` 只做 min-size 布局的绕行写法，Compose 里两个 `weight(1f)` 就是同一效果。
 *
 * 浮层自带不透明底色：它盖在壳层之上，身后没有可依赖的底。
 */
@Composable
fun PasswordOverlay(
    playlistId: String,
    onUnlocked: () -> Unit,
    onEaten: () -> Unit,
    onClose: () -> Unit,
) {
    val app = LocalApp.current
    val c = PullTheme.colors

    // 歌单会改名，标题跟着走：订歌单库，别用一次性读取。
    val playlists by app.playlists.playlists.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == playlistId } ?: app.playlists.find(playlistId)
    val title = TextGuard.mask(playlist?.name ?: "私密歌单")

    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun submit() {
        val id = playlist?.id ?: ""
        if (id.isEmpty()) return
        // 原版 `_submit`：unlock 一次拿到结果（内部先 verify 摘要），不额外调 verify。
        if (app.playlists.unlock(id, code)) {
            error = ""
            onUnlocked()
            return
        }
        // else 分支：写错误文案 + close() + rejected.emit(id) ⇒ 首页随即打开 EatenScreen。
        error = "密码不对"
        onEaten()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            // 原版 SubScreen 的 Margin：左右 PAD_SCREEN、上 64px→25.6dp、下 PAD_SCREEN→16dp
            // （`SubScreen.gd:132-135`）。下留白不能省：两个 weight(1f) 的居中是在这块 Margin
            // 之内算的，少了它整组会往下偏半个 PAD_SCREEN。
            .padding(
                start = Dims.PAD_SCREEN.dp,
                end = Dims.PAD_SCREEN.dp,
                bottom = Dims.PAD_SCREEN.dp,
            ),
    ) {
        // 原版 SubScreen 的 Margin margin_top = 64px → 25.6dp（SubScreen.gd:134）。
        Spacer(Modifier.height(TOP_GAP_DP.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(
                icon = "chevron_down",
                contentDescription = "返回",
                onClick = onClose,
            )
            // 原版 TopBar separation = 20px → 8dp（SubScreen.gd:145）。
            Spacer(Modifier.width(TOPBAR_GAP_DP.dp))
            Text(
                text = title,
                color = c.text,
                fontSize = Typo.TITLE.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        // 原版 Column separation = 22px → 8.8dp（SubScreen.gd:140）。
        Spacer(Modifier.height(BLOCK_GAP_DP.dp))
        Text(text = "这个歌单被设为私密，输入密码才能进入", color = c.textDim, fontSize = Typo.META.sp)

        // TopPad：把这一组压到竖直中线（原版 TopPad/BottomPad 各占一半剩余空间）。
        Spacer(Modifier.weight(1f))

        PasswordField(value = code, onValueChange = { code = it.take(PASSWORD_MAX) }, onSubmit = { submit() })

        // 原版 content 里的顺序是「输入框 → 进入歌单 → 错误提示」，间距 content.separation = 12px → 4.8dp
        // （PasswordScreen.gd:24-71, SubScreen.gd:182）。
        Spacer(Modifier.height(ROW_GAP_DP.dp))
        // 原版 `_confirm`：`primary_button_box()` + 高 120px → 48dp + `FONT_SUB` + `ON_ACCENT`
        // （PasswordScreen.gd:34-47）。走共用件，与 PIN 页的「确认」是同一颗。
        PressButton(
            text = "进入歌单",
            primary = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = { submit() },
        )

        Spacer(Modifier.height(ROW_GAP_DP.dp))
        // 原版这一页的 `_error` **没有**设 `horizontal_alignment`（PasswordScreen.gd:49-55），
        // 而 Label 在 VBox 里横向填满 ⇒ 文案靠左。PIN 页那一个才居中（PasscodeScreen.gd:57）。
        ErrorLabel(error, centered = false)

        Spacer(Modifier.weight(1f))
    }
}

/**
 * 密码输入框：等价原版 `PasswordField`（`LineEdit`，`secret = true`，`PasswordScreen.gd:24-32`）。
 *
 * 用 `BasicTextField` 而不是 material3 的 `OutlinedTextField`：后者内建的下划线/浮动标签
 * 与项目「输入槽 = 圆角矩形 + 1dp 发丝描边」的规矩冲突（`AppTheme.field_box`）。
 * 打开即聚焦（原版 `open_for_playlist` 里的 `_field.grab_focus()`，`:117`），回车即提交
 * （`text_submitted` → `_submit()`，`:32`）。
 *
 * 内边距照抄 `AppTheme._style_line_edit`：左右 34px → 13.6dp、上下 20px → 8dp。
 *
 * 聚焦态也照抄同一处皮肤（`AppTheme.gd:661-665`）：底色换 `SURFACE_HI`、描边换 `ACCENT`
 * （常态是 `SURFACE` + `LINE`）；选中色 `ACCENT_DIM`（`:672`，`selection_color`）。
 */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val c = PullTheme.colors
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    // 同一帧里节点还没挂上时 requestFocus 会抛，包一层以防焦点没拿到就崩。
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = c.accent,
            backgroundColor = c.accentDim,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FIELD_HEIGHT_DP.dp)
                .clip(pullShape(Dims.RADIUS_SMALL))
                .background(if (focused) c.surfaceHi else c.surface)
                .border(
                    width = 1.dp,
                    color = if (focused) c.accent else c.line,
                    shape = pullShape(Dims.RADIUS_SMALL),
                )
                .padding(horizontal = FIELD_PAD_X_DP.dp, vertical = FIELD_PAD_Y_DP.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(text = "输入歌单密码", color = c.textDim, fontSize = Typo.BODY.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                textStyle = TextStyle(color = c.text, fontSize = Typo.BODY.sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 共用件（PlaylistEditorOverlay 的 PIN 页也用这一套）
// ---------------------------------------------------------------------------

/**
 * 圆点指示器：固定 `MAX_LEN` 个槽，已输入的亮强调色。
 *
 * 两种槽的皮肤照抄 `PasscodeScreen._dot_box`（`:184-187`）：
 * 未填 = `field_box(SURFACE, 22)`（**有发丝描边**），已填 = `panel_box(ACCENT, 22)`（只有实底）。
 * 半径 22 恰好是边长 44 的一半 ⇒ 圆点其实是正圆，所以用 `RADIUS_PILL`。
 */
@Composable
internal fun PasscodeDots(code: String) {
    val c = PullTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(PASSCODE_MAX_LEN) { i ->
            if (i > 0) Spacer(Modifier.width(DOT_GAP_DP.dp))
            val filled = i < code.length
            Box(
                modifier = Modifier
                    .size(DOT_SIZE_DP.dp)
                    .clip(pullShape(Dims.RADIUS_PILL))
                    .background(if (filled) c.accent else c.surface)
                    .then(
                        if (filled) {
                            Modifier
                        } else {
                            Modifier.border(1.dp, c.line, pullShape(Dims.RADIUS_PILL))
                        },
                    ),
            )
        }
    }
}

/**
 * 错误提示。空串时占位高度不变，避免键盘整体跳一下（原版 `Label` 即使空串也占一行高）。
 *
 * [centered] 对应原版两页的真实差别：PIN 页设了 `horizontal_alignment = CENTER`
 * （`PasscodeScreen.gd:57`），解锁页**没设**、文案靠左（`PasswordScreen.gd:49-55`）。
 */
@Composable
internal fun ErrorLabel(text: String, centered: Boolean = true) {
    val c = PullTheme.colors
    Box(
        modifier = Modifier.fillMaxWidth().height(16.dp),
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart,
    ) {
        if (text.isNotEmpty()) {
            Text(
                text = text,
                color = c.accent2,
                fontSize = Typo.META.sp,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

/**
 * 数字键盘，3 列：1~9 → 空位/0/删除（`PasscodeScreen.gd:63-92` 的 `GridContainer(columns = 3)`）。
 *
 * 位长保护在内：满 [PASSCODE_MAX_LEN] 后按键无效（原版 `press_digit` 的 cap，`:227-228`），
 * 所以调用方拿到的码串一定合法，不必再自己截断。
 */
@Composable
internal fun PasscodeKeypad(code: String, onCodeChange: (String) -> Unit) {
    val c = PullTheme.colors

    fun push(digit: String) {
        if (code.length < PASSCODE_MAX_LEN) onCodeChange(code + digit)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp)) {
                row.forEach { d ->
                    KeypadKey(
                        text = d,
                        modifier = Modifier.weight(1f),
                        fontSize = Typo.TITLE.sp,
                        color = c.surface,
                    ) { push(d) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp)) {
            // 左下角留空：0 与「删除」的左右位置必须与原版三列网格一致
            // （原版那一格是 `KeypadBlank`，一个 `custom_minimum_size = Vector2(0, KEY_HEIGHT)` 的空 Control，`:73-76`）。
            Spacer(Modifier.weight(1f))
            KeypadKey(
                text = "0",
                modifier = Modifier.weight(1f),
                fontSize = Typo.TITLE.sp,
                color = c.surface,
            ) { push("0") }
            KeypadKey(
                text = "删除",
                modifier = Modifier.weight(1f),
                fontSize = Typo.SUB.sp,
                color = c.surface,
            ) {
                if (code.isNotEmpty()) onCodeChange(code.dropLast(1))
            }
        }
    }
}

/**
 * 单颗键：高 `KEY_HEIGHT = 140px` → 56dp，圆角矩形（胶囊形只留给播放页主键）。
 *
 * 皮肤照抄 `_digit_button` / `KeypadBackspace`（`PasscodeScreen.gd:80-92, 168-181`）：
 * `button_box(SURFACE)` 常态、`button_box(SURFACE_HI)` 悬停/按下。这里用交互源自己实现
 * 按下态（原版没有水波纹，所以 `indication = null`）。
 *
 * 按下缩放 0.88 是原版 `Card.add_press_feedback`（`UIFactory.gd:22-36`）的 `PRESS_SCALE`，
 * 每颗键都挂了它（`PasscodeScreen.gd:91,180`）。时长取 `PRESS_IN/PRESS_OUT`（`:96-97`）。
 */
@Composable
private fun KeypadKey(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = Typo.TITLE.sp,
    color: Color,
    onClick: () -> Unit,
) {
    val c = PullTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PRESS_SCALE else 1f,
        animationSpec = tween(durationMillis = if (pressed) PRESS_IN_MS else PRESS_OUT_MS),
        label = "keypadPress",
    )

    Box(
        modifier = modifier
            .height(PASSCODE_KEY_HEIGHT_DP.dp)
            .scale(scale)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(if (pressed) c.surfaceHi else color)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = c.text, fontSize = fontSize, fontWeight = FontWeight.Medium)
    }
}

/** 原版按下缩放的时长（`AppTheme.gd:96-97`）：`PRESS_IN = 0.09`、`PRESS_OUT = 0.18` 秒。 */
private const val PRESS_IN_MS = 90
private const val PRESS_OUT_MS = 180

/**
 * 本包两页共用的按钮，等价原版 `AppTheme.button_box(...)` / `AppTheme.primary_button_box()`
 * （`AppTheme.gd:580-581, 634-638`）：**只有填充、没有描边**，圆角 `RADIUS_SMALL`，
 * 高 120px → 48dp（`custom_minimum_size.y = 120`），字 `FONT_SUB` → `Typo.SUB`。
 *
 * 主按钮的 `font_color/hover/pressed` 三态都是 `ON_ACCENT`，且 hover/pressed 仍是同一个
 * `primary_button_box()`（`PlaylistEditorScreen.gd:110-114`、`PasscodeScreen.gd:100-104`）
 * ⇒ 主按钮按下**不换底色**；次按钮按下换 `SURFACE_HI`（`:90-92`）。
 *
 * 为什么不用 `components.ActionButton`：那一个次按钮给的是 `surfaceHi` + 1dp 描边、
 * 字号写死 14sp、还有 20/14dp 内边距，与原版（`SURFACE` 实底、无描边、`FONT_SUB`）不一致，
 * 而 `Components.kt` 不在本次允许改动的文件里。
 */
@Composable
internal fun PressButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val c = PullTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PRESS_SCALE else 1f,
        animationSpec = tween(durationMillis = if (pressed) PRESS_IN_MS else PRESS_OUT_MS),
        label = "pressButton",
    )

    val bg: Color = when {
        primary -> c.accent
        pressed -> c.surfaceHi
        else -> c.surface
    }
    val fg: Color = if (primary) c.onAccent else c.text

    Box(
        modifier = modifier
            .height(FIELD_HEIGHT_DP.dp)
            .scale(scale)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = fg, fontSize = Typo.SUB.sp, fontWeight = FontWeight.Medium)
    }
}
