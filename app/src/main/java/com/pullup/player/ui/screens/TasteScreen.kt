package com.pullup.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pullup.player.data.Preferences
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.components.RoundIconButton
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.pullShape
import kotlin.math.abs

/**
 * 口味偏好（独立整页）。移植自 Godot 版 `scripts/ui/TasteScreen.gd`（`extends SubScreen`）。
 *
 * 页面结构逐项照抄 `SubScreen.gd:121-183` 的骨架：
 * Backdrop(BG) → Margin（左右 PAD_SCREEN、上 64px、下 PAD_SCREEN）→ Column(separation 22px) →
 * TopBar → subtitle → Scroll(EXPAND_FILL) → content VBox(separation 12px)。
 * 所以 **TopBar 与副标题不跟着内容滚**，只有下面那一段滚。
 *
 * 内容顺序照抄 `TasteScreen.gd:18-49`：三条权重滑杆 → 自定义口味标签小节 → 新增口味标签行 →
 * 标签流 → 标签面板 → 语言偏好小节 → 语言流 → 导出身份码行 → 导入身份码行 → 身份码面板。
 *
 * 三个承重细节：
 * - **整屏自绘不透明底色**：壳层在下面还活着，不铺底色就会透出首页。
 * - **偏好不是 State**：`Preferences` 是内存镜像 + DataStore，读 `weight()` 不上订阅。
 *   这里订阅 `preferences.changed` 自己记一个 `rev`，用它当 `remember` 的 key 把读数重算一遍
 *   （等价于原版的 `_rebuild_tags()` / `_rebuild_languages()` 手动重铺）。
 * - **文案逐字照抄**：标题、副标题、三条滑杆说明、标签/语种小节说明、全部状态串都对齐 Godot 源。
 *
 * 一处有意的可见性偏差：原版删除标签后把 `已删除「%s」` 写进 `_tag_status`，而那个 Label 长在
 * 默认隐藏的 `TagPanel` 里 —— 面板没展开时这句话用户其实看不见。这里把它显示在标签流下方，
 * 文案本身仍是逐字原文，只是不再让它静默丢掉。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasteScreen() {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors
    val prefs = app.preferences

    val rev = rememberPreferencesRevision(prefs)

    // 标签面板与身份码面板各自独立展开（原版也是两个互不相干的 PanelContainer）。
    var tagPanelOpen by remember { mutableStateOf(false) }
    var codeForExport by remember { mutableStateOf<Boolean?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            // SubScreen 骨架 Margin：左右 PAD_SCREEN(40px)、上 64px→26dp、下 PAD_SCREEN(40px)→16dp。
            .padding(start = Dims.PAD_SCREEN.dp, end = Dims.PAD_SCREEN.dp, top = 26.dp, bottom = 16.dp),
        // SubScreen 骨架 Column separation 22px → 9dp。
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SubTopBar(
            title = "口味偏好",
            subtitle = "只记录在本机、随身份码导出；日推按这里的偏好本地排序——接口不给节奏/旋律/人声数据，用时长、关键词与语种近似判断",
            onClose = { shell.handleBack() },
        )

        // ---- Scroll → content VBox（原版 separation 12px → 5dp）----
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // ---- 三个权重滑杆（`TasteScreen.gd:18-20`）----
            TasteSliderRow(
                kind = "rhythm",
                title = "节奏感",
                hint = "时长偏短的快歌、舞曲/说唱/电子类目更靠前",
                rev = rev,
            )
            TasteSliderRow(
                kind = "melody",
                title = "旋律性",
                hint = "器乐/原声/现场这类靠旋律的、以及更长更完整的更靠前",
                rev = rev,
            )
            TasteSliderRow(
                kind = "vocal",
                title = "人声",
                hint = "纯器乐、伴奏、卡拉OK 会往后排",
                rev = rev,
            )

            // ---- 自定义口味标签（`:22-32`）----
            TasteNote(
                title = "自定义口味标签",
                hint = "命中曲名/歌手/专辑就加权；点标签可删掉；最多 ${Preferences.MAX_TAGS} 个、" +
                    "每个 ${Preferences.MAX_TAG_LENGTH} 字以内",
            )

            TasteRow(
                title = "新增口味标签",
                hint = "例如：city pop / 周杰伦 / 80s / 钢琴",
                onClick = { tagPanelOpen = true },
            )

            TagFlow(rev = rev)

            if (tagPanelOpen) {
                TagInputPanel(onCollapse = { tagPanelOpen = false })
            }

            // ---- 语言偏好（`:34-39`）----
            TasteNote(
                title = "语言偏好",
                hint = "勾选＝加权、取消＝降权；按曲名与歌手名的文字判断（汉字/假名/谚文/拉丁），" +
                    "纯汉字写的日文歌可能被判成华语",
            )

            LanguageFlow(rev = rev)

            // ---- 身份码（`:41-49`）----
            TasteRow(
                title = "导出身份码",
                hint = "把当前权重、标签、语种编码成一串可复制的文本",
                onClick = { codeForExport = true },
            )
            TasteRow(
                title = "导入身份码",
                hint = "粘贴备份或别人分享的身份码",
                onClick = { codeForExport = false },
            )

            codeForExport?.let { forExport ->
                CodePanel(forExport = forExport, onCollapse = { codeForExport = null })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 二级页骨架（对齐 Godot `SubScreen.gd` 的 TopBar + subtitle）
// ---------------------------------------------------------------------------

@Composable
private fun SubTopBar(title: String, subtitle: String, onClose: () -> Unit) {
    val c = PullTheme.colors
    // 原版 TopBar 就是一个 HBox(separation 20px→8dp)，和副标题一起在 content VBox **外面**。
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 本页没有 onClose 形参：壳层的 FullOverlay 负责收起，这里走统一返回逻辑。
            RoundIconButton(
                icon = "chevron_down",
                contentDescription = "收起",
                onClick = onClose,
            )
            Text(
                text = title,
                color = c.text,
                fontSize = Typo.TITLE.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(text = subtitle, color = c.textDim, fontSize = Typo.META.sp)
    }
}

/** 小节说明：标题 + 灰色补充，不套卡片（原版 `_make_note` 的注释：避免整页都是框）。 */
@Composable
private fun TasteNote(title: String, hint: String) {
    val c = PullTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        // 原版 `_make_note` 的 VBox separation 6px → 2dp。
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // caption：FONT_SUB / TEXT
        Text(text = title, color = c.text, fontSize = Typo.SUB.sp)
        // sub：FONT_META / TEXT_DIM，可换行
        Text(text = hint, color = c.textDim, fontSize = Typo.META.sp)
    }
}

/**
 * `SubScreen.make_row(title, hint)` 的 Compose 对照（`SubScreen.gd:60-118`）：
 * 最小高 128px→51dp；`card_box(RADIUS_SMALL)`；内边距左右 34px→14dp；HBox 间距 18px→7dp；
 * 左侧标题 `FONT_BODY`/`TEXT` + 提示 `FONT_META`/`TEXT_DIM`；尾部箭头 **`♪`**
 * （注意不是 `Components.SettingRow` 用的 `›`——原版所有二级页行尾都是这个音符）。
 */
@Composable
private fun TasteRow(title: String, hint: String, onClick: () -> Unit) {
    val c = PullTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 51.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(c.card)
            .border(1.dp, c.line, pullShape(Dims.RADIUS_SMALL))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, color = c.text, fontSize = Typo.BODY.sp)
            if (hint.isNotEmpty()) {
                Text(text = hint, color = c.textDim, fontSize = Typo.META.sp)
            }
        }
        Text(text = "♪", color = c.textDim, fontSize = Typo.SUB.sp)
    }
}

// ---------------------------------------------------------------------------
// 权重滑杆
// ---------------------------------------------------------------------------

/**
 * 权重滑杆行（原版 `_make_slider_row`，`TasteScreen.gd:57-112`）。
 *
 * 原版是一行 **HBox(separation 24px→10dp) 三格**：`[VBox 标题+说明（EXPAND_FILL，垂直居中）]`
 * `[HSlider 340×56px → 136×22dp，垂直居中]` `[数值 Label 76px→30dp 宽、右对齐、FONT_SUB/ACCENT]`。
 * 数值必须在滑杆**旁边**而不是上面，且占固定宽度 —— 否则拖动时数字位数一变，滑杆会左右跳。
 * 滑杆 0..100 / step 5 ⇒ 中间 19 个刻度。
 *
 * 本地 `Float` 是为了拖动跟手：`setWeight` 每次写盘都会让 `rev` 自增，
 * 如果直接拿持久化值当 `value` 会有一次「回弹」。只有外部改动（导入身份码）
 * 落差够大时才把本地值拉回来同步。
 */
@Composable
private fun TasteSliderRow(kind: String, title: String, hint: String, rev: Int) {
    val c = PullTheme.colors
    val prefs = LocalApp.current.preferences

    var local by remember { mutableFloatStateOf(prefs.weight(kind).toFloat()) }
    LaunchedEffect(rev) {
        val stored = prefs.weight(kind).toFloat()
        if (abs(stored - local) > 2f) local = stored
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(c.card)
            .border(1.dp, c.line, pullShape(Dims.RADIUS_SMALL))
            // MarginContainer：左右 34px→14dp、上下 22px→9dp。
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        // row separation 24px → 10dp
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // box：EXPAND_FILL + SHRINK_CENTER，内部 separation 4px → 2dp
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, color = c.text, fontSize = Typo.BODY.sp)
            Text(text = hint, color = c.textDim, fontSize = Typo.META.sp)
        }
        Slider(
            value = local,
            onValueChange = { v ->
                local = v
                prefs.setWeight(kind, v.toInt())
            },
            valueRange = 0f..100f,
            // 0..100 每 5 一档 ⇒ 中间有 19 个刻度。
            steps = 19,
            // 原版 custom_minimum_size = Vector2(340, 56) → 136×22dp。
            modifier = Modifier.width(136.dp),
            colors = SliderDefaults.colors(
                thumbColor = c.accent,
                activeTrackColor = c.accent,
                inactiveTrackColor = c.track,
            ),
        )
        // value：custom_minimum_size = Vector2(76, 0) → 30dp 宽，HORIZONTAL_ALIGNMENT_RIGHT。
        Text(
            text = "%d".format(local.toInt()),
            color = c.accent,
            fontSize = Typo.SUB.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 标签
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagFlow(rev: Int) {
    val c = PullTheme.colors
    val prefs = LocalApp.current.preferences
    val tags = remember(rev) { prefs.tags() }
    var status by remember { mutableStateOf("") }

    if (tags.isEmpty()) {
        // 空态：原版往流里塞一行灰字（`_rebuild_tags`，`:298-302`）。
        Text(
            text = "还没有标签：日推先按上面的权重和语种排序",
            color = c.textDim,
            fontSize = Typo.META.sp,
        )
        return
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        // HFlowContainer h/v_separation 12px → 5dp。
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        tags.forEach { tag ->
            // chip 文本按原版：`"%s  ×" % key`（key 后两个空格 + ×）。点一下即删。
            Chip(
                text = "$tag  ×",
                active = true,
                onClick = {
                    prefs.removeTag(tag)
                    status = "已删除「$tag」"
                },
            )
        }
    }
    // 删除后的状态串沿用原版文案；只在有内容时占位，避免空行。
    if (status.isNotEmpty()) {
        Text(text = status, color = c.textDim, fontSize = Typo.META.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageFlow(rev: Int) {
    val prefs = LocalApp.current.preferences
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        // HFlowContainer h/v_separation 12px → 5dp。
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Preferences.LANGUAGE_CODES.forEach { code ->
            val on = remember(rev) { prefs.languageEnabled(code) }
            val label = Preferences.LANGUAGE_LABELS[code] ?: code
            // chip 文本按原版：`"%s %s" % [label, "✓" if on else "✕"]`。
            Chip(
                text = "$label ${if (on) "✓" else "✕"}",
                active = on,
                onClick = { prefs.setLanguageEnabled(code, !on) },
            )
        }
    }
}

/**
 * 通用小胶囊（原版 `_make_chip`，`:333-344`）：选中＝`ACCENT` 实底 + `BG` 字色，
 * 未选＝`SURFACE_HI` + `TEXT_DIM` 字色；两者都是 `RADIUS_SMALL` 圆角矩形、**不是胶囊**，
 * 内边距取 `panel_box(..., 30)` 的 30px → 12dp。
 */
@Composable
private fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    val c = PullTheme.colors
    Text(
        text = text,
        color = if (active) c.bg else c.textDim,
        fontSize = Typo.SUB.sp,
        modifier = Modifier
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(if (active) c.accent else c.surfaceHi)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

/**
 * 标签输入面板（原版 `_make_tag_panel`，`:224-273`）：无标题行内面板，
 * 结构是 `card_box(RADIUS_SMALL)` → Margin(34/34/22/22 → 14/14/9/9) → VBox(separation 14px→6dp)
 * → 输入框（min 高 96px→38dp）+ 按钮行（separation 14px→6dp，两枚按钮 min 高 88px→35dp）+ 状态行。
 *
 * `addTag` 返回空串＝成功，否则就是给用户看的原因，直接当状态文案用（不加工）。
 */
@Composable
private fun TagInputPanel(onCollapse: () -> Unit) {
    val c = PullTheme.colors
    val prefs = LocalApp.current.preferences
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("输入一个词：日推里曲名/歌手/专辑命中它就算一次加权") }
    var statusAccent by remember { mutableStateOf(false) }

    fun submit() {
        val raw = text.trim()
        val reason = prefs.addTag(raw)
        if (reason.isEmpty()) {
            text = ""
            status = "已添加「$raw」：日推里命中它的歌会靠前"
            statusAccent = true
        } else {
            status = reason
            statusAccent = false
        }
    }

    PanelCard {
        TasteField(
            value = text,
            onValueChange = { if (it.length <= Preferences.MAX_TAG_LENGTH) text = it },
            placeholder = "例如：city pop",
            imeAction = ImeAction.Done,
            onSubmit = { submit() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelButton(text = "添加", primary = true, onClick = { submit() })
            PanelButton(text = "收起", primary = false, onClick = onCollapse)
        }
        Text(
            text = status,
            color = if (statusAccent) c.accent else c.textDim,
            fontSize = Typo.META.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// 身份码
// ---------------------------------------------------------------------------

/**
 * 身份码面板（原版 `_make_code_panel` / `_show_code` / `_do_import`，`:115-182`）。
 * 导出＝把码填进输入框并给出「全选后复制」的说明；导入＝清空输入框，
 * 点 `导入` 把 `importCode` 返回的原因原样当状态。
 *
 * 面板自己订阅 `Preferences.changed`（`rev`）：导入成功会改掉权重/标签/语种，
 * 而 TasteScreen 外层的滑块/标签流用的是同一个令牌，面板内的导出码也必须跟着重算
 * —— 否则导入后切到导出面板看到的还是旧码（原版每次 `_show_code` 都重新调
 * `export_code()`，等价于「每次展开都是最新值」）。
 */
@Composable
private fun CodePanel(forExport: Boolean, onCollapse: () -> Unit) {
    val c = PullTheme.colors
    val prefs = LocalApp.current.preferences
    var text by remember(forExport) {
        mutableStateOf(if (forExport) prefs.exportCode() else "")
    }
    var status by remember(forExport) {
        mutableStateOf(if (forExport) "全选后复制即可带走（不含任何账号信息）" else "粘贴身份码后点「导入」")
    }
    var statusAccent by remember(forExport) { mutableStateOf(false) }

    // 导入成功后要按新偏好重铺：与滑杆/标签流同一个令牌（`rememberPreferencesRevision`）。
    val rev = rememberPreferencesRevision(prefs)
    // 导出面板的内容在导入后也会变（权重改了码就变了），同令牌重算。
    val exportText = remember(rev) { prefs.exportCode() }

    PanelCard {
        TasteField(
            value = if (forExport) exportText else text,
            onValueChange = { if (!forExport) text = it },
            placeholder = "把身份码粘贴到这里",
            imeAction = ImeAction.Done,
            onSubmit = {},
            // 导出时才自动全选：导入是来粘贴的，全选反而会盖掉剪贴板内容。
            selectAllOnStart = forExport,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelButton(text = "导入", primary = true, onClick = {
                // 原版 `_do_import`（`TasteScreen.gd:175-182`）是**不清输入框**的：
                // 只改状态文案。这里照做，不清 text —— 用户可能想再看一眼刚贴的码。
                val reason = prefs.importCode(text)
                if (reason.isEmpty()) {
                    status = "导入成功，偏好已更新"
                    statusAccent = true
                } else {
                    status = "导入失败：$reason"
                    statusAccent = false
                }
            })
            PanelButton(text = "收起", primary = false, onClick = onCollapse)
        }
        Text(
            text = status,
            color = if (statusAccent) c.accent else c.textDim,
            fontSize = Typo.META.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// 局部小组件
// ---------------------------------------------------------------------------

/** 面板底：`card_box(RADIUS_SMALL)` + Margin(34/34/22/22 → 14/14/9/9) + VBox(separation 14px→6dp)。 */
@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    val c = PullTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(c.card)
            .border(1.dp, c.line, pullShape(Dims.RADIUS_SMALL))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** 矩形圆角输入框：`field_box` 同款（SURFACE 底 + 发丝边 + `RADIUS_SMALL`），**不用胶囊**；min 高 96px→38dp。 */
@Composable
private fun TasteField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onSubmit: () -> Unit,
    /**
     * 进场即全选（原版 `_code_input.select_all()`，`TasteScreen.gd:162`）。
     *
     * 导出身份码时用：原版没有剪贴板按钮（全工程 grep `DisplayServer.clipboard` 零命中），
     * 它的做法是把码填进输入框并全选，用户按系统的「复制」就能带走 —— 这里照做。
     */
    selectAllOnStart: Boolean = false,
) {
    val c = PullTheme.colors
    val shape = RoundedCornerShape(Dims.RADIUS_SMALL.dp)
    val focusRequester = remember { FocusRequester() }
    // 文本仍以外部传入的 value 为准（长度上限之类的拦截在调用方），这里只额外记住光标/选区。
    // 必须把光标夹回文本长度内：调用方可能拒绝这次输入（如标签超过 16 字），
    // 此时 value 没变而输入法给的光标位置已经越界，直接用会踩到非法 TextRange。
    var selection by remember { mutableStateOf(TextRange.Zero) }
    val safeSelection = run {
        val start = selection.start.coerceIn(0, value.length)
        val end = selection.end.coerceIn(0, value.length)
        TextRange(start, end)
    }
    if (selectAllOnStart) {
        // 进场即全选（原版 `_code_input.select_all()`，`TasteScreen.gd:162`）：先拿焦点，再全选。
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            selection = TextRange(0, value.length)
        }
    }
    BasicTextField(
        value = TextFieldValue(text = value, selection = safeSelection),
        onValueChange = {
            selection = it.selection
            onValueChange(it.text)
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selectAllOnStart) Modifier.focusRequester(focusRequester) else Modifier),
        singleLine = true,
        textStyle = TextStyle(color = c.text, fontSize = Typo.BODY.sp),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    .clip(shape)
                    .background(c.surface)
                    .border(1.dp, c.line, shape)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, color = c.textDim, fontSize = Typo.BODY.sp)
                }
                inner()
            }
        },
    )
}

/**
 * 面板内按钮（原版 Button：`_button_box(SURFACE)` 内边距 30/18 → 12/7dp，
 * primary 走 `primary_button_box()` 的 ACCENT 实底 + `ON_ACCENT` 字色）。
 * 原版 `custom_minimum_size = Vector2(0, 88)` → 最小高 35dp。
 */
@Composable
private fun PanelButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val c = PullTheme.colors
    Text(
        text = text,
        color = if (primary) c.onAccent else c.textDim,
        fontSize = Typo.SUB.sp,
        modifier = Modifier
            .heightIn(min = 35.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(if (primary) c.accent else c.surfaceHi)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/**
 * 订阅 `Preferences.changed` 并返回一个自增的版本号，用来当 `remember` 的 key。
 * DataStore 的 data 流订阅即刻发一次当前值，之后每次写入再发一次 —— 正好当重算令牌用。
 */
@Composable
internal fun rememberPreferencesRevision(prefs: Preferences): Int {
    var rev by remember { mutableIntStateOf(0) }
    LaunchedEffect(prefs) { prefs.changed.collect { rev++ } }
    return rev
}
