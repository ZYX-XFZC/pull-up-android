package com.pullup.player.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.data.CustomArt
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.components.PullSwitch
import com.pullup.player.ui.components.RoundIconButton
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.pullShape
import java.io.File

/**
 * 外观（独立整页）。移植自 Godot 版 `scripts/ui/AppearanceScreen.gd`（`extends SubScreen`）。
 *
 * 八行**按原版顺序**排列（`AppearanceScreen.gd:25-79`）：自定义封面 / 自定义背景 / 恢复默认封面 /
 * 恢复默认背景 / 动效速度 / 面板质感 / 深色模式 / 启动动画，最后是状态行（`:81-86`）。
 * 原版这些行是 `cycle_*` 循环式取值（点一下切下一档），所以这里也照抄成「行右侧显示当前值」，
 * 而不是改成分段选择器。
 *
 * SubScreen 骨架逐项照抄（`SubScreen.gd:121-183`）：Backdrop(BG) → Margin（左右 PAD_SCREEN、
 * 上 64px、下 PAD_SCREEN）→ Column(spacing 22px) → TopBar → subtitle → Scroll(EXPAND_FILL) →
 * content VBox(spacing 12px)。所以 **TopBar 与副标题不随内容滚动**，只有下面的行滚动。
 *
 * 两条承重细节：
 * - **整屏自绘不透明底色**：壳层在下面还活着，不铺底色就会透出首页。
 * - **不手动重建调色板**：`PullUpTheme` 已经订阅 `preferences.changed` 并 `applyMode` /
 *   `applyPanelStyle` / `applyMotionScale`，这里只管调 setter —— 整棵树会原地换色。
 *   如果在这里再自己算一遍颜色，反而会和主题层打架（原版注释也写明「切换后界面重建一次」）。
 *
 * 移植差异（语义等价）：
 * - 原版选图走 Godot `FileDialog`（`ACCESS_FILESYSTEM`，过滤 png/jpg/jpeg/webp）；Android 侧对等物
 *   是系统图片选择器 `ActivityResultContracts.GetContent`，它回的是 `content://` 而不是文件路径，
 *   而 `CustomArt.setCoverFromPath` 要的是真实路径（内部 `File(path).exists()`），
 *   所以先由本页把字节复制到 `cacheDir` 的临时文件，再把那个路径交出去。
 * - `恢复默认背景` 在 Kotlin 版只需 `CustomArt.resetBackground()`：原版额外调的
 *   `AmbientBackground.reset_to_default()` 是因为 Godot 的环境背景会跟着封面渐变；
 *   Kotlin 的 `ui/components/AmbientBackground.kt` 是纯光斑、不读 `CustomArt`，没有对应状态要清。
 * - 值文案也可以直接读 `preferences.darkModeLabel()/bootAnimationLabel()`：数据层已按原版
 *   （`scripts/Preferences.gd:291`、`:347`）返回「已开启/已关闭」「开/关」。这里仍就地算，
 *   只是为了让本页的取值点一眼可见，与那两个方法逐字一致。
 */
@Composable
fun AppearanceScreen() {
    val c = PullTheme.colors
    val shell = LocalShell.current
    val app = LocalApp.current
    val prefs = app.preferences
    val context = LocalContext.current

    // 偏好是内存镜像 + DataStore，读方法不订阅；靠这个版本号当 key 把读数重算一遍。
    val rev = rememberPreferencesRevision(prefs)
    // 等价原版 `CustomArt.changed.connect(_refresh)`：两个状态流各订一份，值一变就重读 has*()。
    val customCover by app.customArt.cover.collectAsStateWithLifecycle()
    val customBackground by app.customArt.background.collectAsStateWithLifecycle()

    var status by remember { mutableStateOf("") }
    // 这次选图是给封面还是背景（原版 `_picking_cover`）。
    var pickingCover by remember { mutableStateOf(true) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // content:// → cacheDir 临时文件：CustomArt 只认文件路径。
        val dest = File(context.cacheDir, "appearance_pick.png")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: error("打不开这张图")
        }.isSuccess
        if (!copied || dest.length() == 0L) {
            status = "设置失败：${CustomArt.ERR_NOT_FOUND}"
            return@rememberLauncherForActivityResult
        }
        // 逐字照抄原版 `_on_image_selected`（`:105-111`）。
        val reason = if (pickingCover) {
            app.customArt.setCoverFromPath(dest.absolutePath)
        } else {
            app.customArt.setBackgroundFromPath(dest.absolutePath)
        }
        status = if (reason.isEmpty()) {
            if (pickingCover) "已设为自定义封面" else "已设为自定义背景"
        } else {
            "设置失败：$reason"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            // SubScreen 骨架 Margin：左右 PAD_SCREEN、上 64px→25.6dp、下 PAD_SCREEN→16dp。
            .padding(start = Dims.PAD_SCREEN.dp, end = Dims.PAD_SCREEN.dp, top = 25.6.dp, bottom = 16.dp),
        // SubScreen 骨架 Column separation 22px → 8.8dp。
        verticalArrangement = Arrangement.spacedBy(8.8.dp),
    ) {
        // ---- TopBar：返回圆钮 + 标题（原版 `title_label`：FONT_TITLE/TEXT）----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            // SubScreen TopBar HBox separation 20px → 8dp。
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 本页没有 onClose 形参：壳层的 FullOverlay 负责收起，这里走统一返回逻辑。
            RoundIconButton(
                icon = "chevron_down",
                contentDescription = "收起",
                onClick = { shell.handleBack() },
            )
            Text(
                text = "外观",
                color = c.text,
                fontSize = Typo.TITLE.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ---- 副标题（原版 `subtitle_label`：FONT_META/TEXT_DIM，逐字照抄 `:23`）----
        Text(
            text = "封面与背景都能换成自己的图；动效速度影响全局动画快慢",
            color = c.textDim,
            fontSize = Typo.META.sp,
        )

        // ---- Scroll → content VBox（原版 separation 12px → 4.8dp）----
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.8.dp),
        ) {
            // 行 1：自定义封面
            AppearanceRow(
                title = "自定义封面",
                hint = "用自己的图替换播放页封面",
                value = if (customCover != null || app.customArt.hasCover()) "已自定义" else "默认",
                onClick = {
                    pickingCover = true
                    pickImage.launch("image/*")
                },
            )

            // 行 2：自定义背景
            AppearanceRow(
                title = "自定义背景",
                hint = "用自己的图替换随歌渐变",
                value = if (customBackground != null || app.customArt.hasBackground()) "已自定义" else "默认",
                onClick = {
                    pickingCover = false
                    pickImage.launch("image/*")
                },
            )

            // 行 3：恢复默认封面
            AppearanceRow(
                title = "恢复默认封面",
                hint = "改回歌曲自带的封面",
                value = "",
                onClick = {
                    app.customArt.resetCover()
                    status = "已恢复默认封面"
                },
            )

            // 行 4：恢复默认背景
            AppearanceRow(
                title = "恢复默认背景",
                hint = "清除自定义背景，回到随封面变色",
                value = "",
                onClick = {
                    app.customArt.resetBackground()
                    status = "已恢复默认背景"
                },
            )

            // 行 5：动效速度（原版 `cycle_motion_scale()`）
            AppearanceRow(
                title = "动效速度",
                hint = "0.5×–2×，改完立刻生效",
                value = remember(rev) { "%.2f×".format(prefs.motionScale()) },
                onClick = { prefs.cycleMotionScale() },
            )

            // 行 6：面板质感（原版 `cycle_panel_style()`）
            AppearanceRow(
                title = "面板质感",
                hint = "亚克力最透、云母中等、纯色完全不透明",
                value = remember(rev) { prefs.panelStyleName() },
                onClick = { prefs.cyclePanelStyle() },
            )

            // 行 7：深色模式（原版 `toggle_dark_mode()`，值是「已开启/已关闭」）
            // 用户 m05292 项①：文字值改成滑动开关，整行仍可点（点行=点开关）。
            AppearanceRow(
                title = "深色模式",
                hint = "开＝深色界面，关＝浅色界面；切换后界面重建一次",
                trailing = {
                    PullSwitch(
                        checked = remember(rev) { prefs.darkMode() },
                        onCheckedChange = { prefs.toggleDarkMode() },
                    )
                },
                onClick = { prefs.toggleDarkMode() },
            )

            // 行 8：启动动画（原版 `cycle_boot_animation()`，值是「开/关」）
            AppearanceRow(
                title = "启动动画",
                hint = "开＝启动时盖一层入场动画；关＝直接进首页",
                trailing = {
                    PullSwitch(
                        checked = remember(rev) { prefs.bootAnimation() },
                        onCheckedChange = { prefs.cycleBootAnimation() },
                    )
                },
                onClick = { prefs.cycleBootAnimation() },
            )

            // ---- 状态行（原版 `_status`：初始空串，FONT_META/TEXT_DIM，可换行）----
            if (status.isNotEmpty()) {
                Text(text = status, color = c.textDim, fontSize = Typo.META.sp)
            }
        }
    }
}

/**
 * `SubScreen.make_row(title, hint)` 的 Compose 对照。
 *
 * 逐项对齐 `SubScreen.gd:60-118`：最小高 128px→51.2dp；`card_box(RADIUS_SMALL)`（卡片底 + 发丝边）；
 * 内边距左右 34px→13.6dp；HBox 间距 18px→7.2dp；左侧 VBox（标题 `FONT_BODY`/`TEXT`、
 * 提示 `FONT_META`/`TEXT_DIM`，间距 4px→1.6dp）；右侧值 `FONT_SUB`/`ACCENT`；尾部箭头 **`♪`**
 * （`FONT_SUB`/`TEXT_DIM`——注意不是 `Components.SettingRow` 用的 `›`）。
 *
 * 用户 m05292 项①之后的两种尾部形态：[value] 非空 = 原来的文字值（动效速度/面板质感），
 * [trailing] 非空 = 滑动开关（深色模式/启动动画），此时文字值留空、`♪` 也不画 ——
 * 开关自己就是尾部控件，再挂个箭头显得多余。整行仍可点，点行等同于点开关。
 */
@Composable
private fun AppearanceRow(
    title: String,
    hint: String,
    value: String = "",
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = PullTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 51.2.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(c.card)
            .border(1.dp, c.line, pullShape(Dims.RADIUS_SMALL))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.2.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.6.dp),
        ) {
            Text(text = title, color = c.text, fontSize = Typo.BODY.sp)
            if (hint.isNotEmpty()) {
                Text(text = hint, color = c.textDim, fontSize = Typo.META.sp)
            }
        }
        if (trailing != null) {
            trailing()
        } else if (value.isNotEmpty()) {
            Text(text = value, color = c.accent, fontSize = Typo.SUB.sp)
            Text(text = "♪", color = c.textDim, fontSize = Typo.SUB.sp)
        }
    }
}
