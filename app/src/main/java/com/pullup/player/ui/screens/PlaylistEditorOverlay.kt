package com.pullup.player.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pullup.player.data.TextGuard
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.components.Cover
import com.pullup.player.ui.components.RoundIconButton
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.pullShape
import java.io.File

// ---------------------------------------------------------------------------
// 几何常量（`scripts/ui/AppTheme.gd:63` 逐字：「我们的画布 1080px = 432dp，
// 即 1dp = 2.5px」⇒ Godot 的 px ÷ 2.5 = Compose 的 dp）
// ---------------------------------------------------------------------------

/** 封面预览边长：原版 `COVER_PREVIEW := 240.0`（`PlaylistEditorScreen.gd:17`）→ 96dp。 */
private const val COVER_PREVIEW_DP = 96

/**
 * 封面预览圆角：原版走 `Card.make_art(_cover_path, COVER_PREVIEW, ACCENT_2)`
 * （`PlaylistEditorScreen.gd:45,188`），`radius` 取默认值 `ART_RADIUS := 10`
 * （`UIFactory.gd:150`）→ 10 ÷ 2.5 = 4dp。**不是**卡片圆角 `Dims.RADIUS`。
 */
private const val COVER_RADIUS_DP = 4

/** 名字上限：原版 `_name_field.max_length = 24`（`PlaylistEditorScreen.gd:79`，与 `Playlists.MAX_NAME` 同数）。 */
private const val NAME_MAX = 24

/** 二级页骨架 `Margin.margin_top = 64`（`SubScreen.gd:134`）→ 25.6dp。 */
private const val TOP_GAP_DP = 25.6

/** 二级页骨架 `Column.separation = 22`（`SubScreen.gd:140`）→ 8.8dp。 */
private const val BLOCK_GAP_DP = 8.8

/** 二级页骨架 `TopBar.separation = 20`（`SubScreen.gd:145`）→ 8dp。 */
private const val TOPBAR_GAP_DP = 8.0

/** 二级页骨架 `content.separation = 12`（`SubScreen.gd:182`）→ 4.8dp。 */
private const val CONTENT_GAP_DP = 4.8

/** 按钮/输入框 `custom_minimum_size.y = 120`（`PlaylistEditorScreen.gd:59,80,88,108`）→ 48dp。 */
private const val CONTROL_HEIGHT_DP = 48

/** `CoverRow` 的 `separation = 30`（`PlaylistEditorScreen.gd:42`）→ 12dp。 */
private const val COVER_ROW_GAP_DP = 12.0

/** 封面右侧那一列 `cover_column.separation = 12`（`PlaylistEditorScreen.gd:52`）→ 4.8dp。 */
private const val COVER_COL_GAP_DP = 4.8

/**
 * `LineEdit` 皮肤的内边距：`AppTheme._style_line_edit` 给左右 `content_margin = 34`、
 * 上下 `20`（`AppTheme.gd:655-658`）→ 13.6dp / 8dp。
 */
private const val FIELD_PAD_X_DP = 13.6
private const val FIELD_PAD_Y_DP = 8.0

/**
 * 新建歌单整页浮层，移植自 `scripts/ui/PlaylistEditorScreen.gd`。
 *
 * 逐字照抄的文案：标题「新建歌单」、副标题「给歌单起个名字、挑一张封面；想藏起来就设为私密并设一个密码」、
 * 「选择封面」「不选就用默认封面」「歌单名称」「设置为私密」「已设为私密 · 点一下取消」「创建歌单」，
 * 以及三条失败提示「先给歌单起个名字」「私密歌单要设一个密码」「选中的封面文件找不到了，重新选一张」。
 *
 * 两处与原版不同、但语义等价的移植：
 * - 原版封面走 Godot `FileDialog`（`_dialog.popup_centered_ratio(0.85)`，`:171-172`）选一个图片**文件**；
 *   Android 侧对等物是系统图片选择器（`ActivityResultContracts.GetContent`）。选回来的 `content://`
 *   不是文件路径，而 `Playlists.create` 的 `coverSrc` 要的是真实路径（内部 `File(src).exists()`），
 *   所以先由本页把字节复制到 `cacheDir` 的临时文件，再把那个路径交出去。
 *   —— 这条复制只为本页服务，不碰数据层（`copyCover` 仍负责缩放与落盘）。
 * - 原版「设置为私密」弹的是 `PasscodeScreen`（PIN 键盘，`:98-102, 207`），不是行内密码框
 *   （用户 m00675 ⑤ 明确改掉的写法）。这里同样整页盖一层 PIN 输入，确认后回填。
 *
 * 本层自带不透明底色；关闭走 `LocalShell.current.handleBack()`（弹栈顶，等价原版 `close()`）。
 *
 * [onCreated] 对应原版 `PlaylistEditorScreen.gd:14` 的 `signal created(id)`（`:249` `created.emit(id)`）——
 * 「添加到歌单」面板点「新建歌单」时，新歌单一建好就要把这首先前那首放进去
 * （`ActionSheet.gd:248-258` 的 `_pending_add_after_create` + `_on_playlist_created`）。
 * 首页那条「新建歌单」入口不关心建完之后干什么，所以缺省是空实现。
 */
@Composable
fun PlaylistEditorOverlay(onCreated: (String) -> Unit = {}) {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors
    val context = LocalContext.current

    var name by remember { mutableStateOf("") }
    var coverPath by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pinOpen by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // content:// → cacheDir 临时文件：create() 只认文件路径。
        val dest = File(context.cacheDir, "cover_pick.png")
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: error("打不开这张图")
        }.isSuccess
        if (ok && dest.length() > 0L) {
            coverPath = dest.absolutePath
            status = ""
        } else {
            status = "选中的封面文件找不到了，重新选一张"
        }
    }

    // ---- PIN 层：盖在本页之上（原版把 PasscodeScreen 作为子节点 add_child 上来，PlaylistEditorScreen.gd:98-102）----
    if (pinOpen) {
        PinSetupPage(
            onCancel = { pinOpen = false },
            onConfirm = { code ->
                password = code
                isPrivate = true
                pinOpen = false
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(
                start = Dims.PAD_SCREEN.dp,
                end = Dims.PAD_SCREEN.dp,
                top = TOP_GAP_DP.dp,
                bottom = Dims.PAD_SCREEN.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(BLOCK_GAP_DP.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(
                icon = "chevron_down",
                contentDescription = "返回",
                onClick = { shell.handleBack() },
            )
            Spacer(Modifier.width(TOPBAR_GAP_DP.dp))
            Text(text = "新建歌单", color = c.text, fontSize = Typo.TITLE.sp)
        }

        Text(
            text = "给歌单起个名字、挑一张封面；想藏起来就设为私密并设一个密码",
            color = c.textDim,
            fontSize = Typo.META.sp,
        )

        // Scroll：原版 content 在 ScrollContainer 里，只按最小尺寸排版，滚到哪算哪。
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CONTENT_GAP_DP.dp),
        ) {
            // 封面行（原版 CoverRow，HBox separation 30px → 12dp）：预览 + 「选择封面」+ 提示。
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 空路径交给 Cover 画原版那块 DefaultArt 占位封面（等价 `Card.make_art("", ...)`）。
                Cover(url = coverPath, size = COVER_PREVIEW_DP, corner = COVER_RADIUS_DP)
                Spacer(Modifier.width(COVER_ROW_GAP_DP.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(COVER_COL_GAP_DP.dp),
                ) {
                    PressButton(
                        text = "选择封面",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { pickImage.launch("image/*") },
                    )
                    Text(
                        text = if (coverPath.isNotEmpty()) File(coverPath).name else "不选就用默认封面",
                        color = c.textDim,
                        fontSize = Typo.META.sp,
                    )
                }
            }

            NameField(value = name, onValueChange = { name = it.take(NAME_MAX) })

            // 私密开关：已是私密时再点＝取消私密并清掉密码（原版 _toggle_private，`:199-207`）。
            PressButton(
                text = if (isPrivate) "已设为私密 · 点一下取消" else "设置为私密",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (isPrivate) {
                        isPrivate = false
                        password = ""
                    } else {
                        pinOpen = true
                    }
                },
            )

            PressButton(
                text = "创建歌单",
                primary = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    // 校验顺序与原版 `_create_playlist` 完全一致，且每条各有一句原文案（`:235-245`）。
                    val clean = name.trim()
                    if (clean.isEmpty()) {
                        status = "先给歌单起个名字"
                        return@PressButton
                    }
                    if (isPrivate && password.trim().isEmpty()) {
                        status = "私密歌单要设一个密码"
                        return@PressButton
                    }
                    if (coverPath.isNotEmpty() && !File(coverPath).exists()) {
                        status = "选中的封面文件找不到了，重新选一张"
                        return@PressButton
                    }
                    val id = app.playlists.create(clean, coverPath, isPrivate, password)
                    if (id.isEmpty()) {
                        // create 失败时把中文原因留在 lastError（封面读不出/写不进都是它）。
                        status = TextGuard.mask(app.playlists.lastError.ifEmpty { "没能建起来" })
                        return@PressButton
                    }
                    status = ""
                    // 先发 created（原版 `:249`），再关页 —— 回调里要拿 id 往歌单里塞歌。
                    onCreated(id)
                    shell.handleBack()
                },
            )

            if (status.isNotEmpty()) {
                Text(text = status, color = c.accent2, fontSize = Typo.META.sp)
            }
        }
    }
}

/**
 * 名称输入框，等价原版 `NameField`（`LineEdit`，占位「歌单名称」，最长 24，高 120px → 48dp）。
 *
 * 用 `BasicTextField` 而不是 material3 的 `OutlinedTextField`：后者的内建
 * 下划线/浮动标签与项目「卡片 = 1dp 细线描边 + 圆角」的规矩冲突，
 * 而这里要的只是一个方方正正的输入槽（原版就是一个 120px 高的 LineEdit）。
 *
 * 聚焦态照抄 `AppTheme._style_line_edit`（`AppTheme.gd:661-665`）：底色 `SURFACE_HI`、
 * 描边 `ACCENT`（常态 `SURFACE` + `LINE`）；选中色 `ACCENT_DIM`（`:672`）。
 */
@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit) {
    val c = PullTheme.colors
    var focused by remember { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = c.accent,
            backgroundColor = c.accentDim,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CONTROL_HEIGHT_DP.dp)
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
                Text(text = "歌单名称", color = c.textDim, fontSize = Typo.BODY.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = Typo.BODY.sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
            )
        }
    }
}

/**
 * 设密码的 PIN 页，移植自 `scripts/ui/PasscodeScreen.gd`（它本身就是一整个 `SubScreen`，
 * 所以这一页自带返回钮、标题、副标题与自己的骨架间距）。
 *
 * 文案照抄：标题「输入密码」、副标题「4~6 位数字，之后打开这个歌单都要输它」、
 * 按钮「确认」、不够长时报「至少 4 位数字」（`MIN_LEN := 4`，`:20, 249`）。
 * 这一页的返回圆钮＝取消（原版 `_on_cancel` 只发 `cancelled`，什么都不改，`:270-273`）。
 *
 * 内容自上而下（`PasscodeScreen.gd:32-107`）：圆点指示 → 错误提示 → 数字键盘 → 确认键，
 * 首尾各垫一个可伸缩空块让这一组垂直居中（原版 `TopPad`/`BottomPad`，`:109-124`）。
 * 四块之间一律 `content.separation = 12px → 4.8dp`。
 */
@Composable
private fun PinSetupPage(onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    val c = PullTheme.colors
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(
                start = Dims.PAD_SCREEN.dp,
                end = Dims.PAD_SCREEN.dp,
                top = TOP_GAP_DP.dp,
                bottom = Dims.PAD_SCREEN.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(BLOCK_GAP_DP.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(
                icon = "chevron_down",
                contentDescription = "返回",
                onClick = onCancel,
            )
            Spacer(Modifier.width(TOPBAR_GAP_DP.dp))
            Text(text = "输入密码", color = c.text, fontSize = Typo.TITLE.sp)
        }

        Text(
            text = "$PASSCODE_MIN_LEN~$PASSCODE_MAX_LEN 位数字，之后打开这个歌单都要输它",
            color = c.textDim,
            fontSize = Typo.META.sp,
        )

        // TopPad / DotWrap / ErrorLabel / Keypad / ConfirmButton / BottomPad（原版 content 的六个孩子）。
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(CONTENT_GAP_DP.dp),
        ) {
            Spacer(Modifier.weight(1f))
            PasscodeDots(code = code)
            // 原版这一页的 `_error` 设了 `horizontal_alignment = CENTER`（PasscodeScreen.gd:57）。
            ErrorLabel(error, centered = true)
            PasscodeKeypad(code = code, onCodeChange = {
                code = it
                // 原版 `press_digit` 每按一次都清空错误（`:230`）。
                if (error.isNotEmpty()) error = ""
            })
            PressButton(
                text = "确认",
                primary = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (code.length < PASSCODE_MIN_LEN) {
                        error = "至少 $PASSCODE_MIN_LEN 位数字"
                    } else {
                        error = ""
                        onConfirm(code)
                    }
                },
            )
            Spacer(Modifier.weight(1f))
        }
    }
}
