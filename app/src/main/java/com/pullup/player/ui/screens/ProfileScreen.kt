package com.pullup.player.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pullup.player.data.TextGuard
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.components.HairLine
import com.pullup.player.ui.components.SectionHeader
import com.pullup.player.ui.components.SettingRow
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.pullShape

/** 与本模块 `build.gradle.kts` 的 `versionName` 保持一致（未开 buildConfig，不引 BuildConfig）。 */
private const val APP_VERSION = "1.0"

/** 作者主页（原版 ProfileScreen.gd 里的 AUTHOR_HOMEPAGE，逐字照抄）。 */
private const val AUTHOR_HOMEPAGE = "https://space.bilibili.com/1204762748"

/** `Content` 上边距 72px（`ProfileScreen.gd:56`）⇒ 29dp；左右走 `Dims.PAD_SCREEN`，
 *  下边距原版是 −366px（给迷你条 + 底栏让位），Kotlin 侧由外壳承担（`PullUpRoot.kt:189-200`）。 */
private const val CONTENT_TOP_DP = 29

/** `Content` 间距 22px（`ProfileScreen.gd:58`）⇒ 9dp。 */
private const val CONTENT_GAP_DP = 9

/** `Sections` 间距 30px（`ProfileScreen.gd:80`）⇒ 12dp。 */
private const val SECTION_GAP_DP = 12

/** 同一个段里两行之间的间距 4px（`_make_group()`，`ProfileScreen.gd:123`）⇒ 2dp。 */
private const val GROUP_GAP_DP = 2

/** 关于卡片内边距：左右 34px / 上下 28px（`ProfileScreen.gd:200-203`）⇒ 14 / 11dp。 */
private const val ABOUT_PAD_H_DP = 14
private const val ABOUT_PAD_V_DP = 11

/** 关于卡片内部间距 14px（`ProfileScreen.gd:207`）⇒ 6dp。 */
private const val ABOUT_GAP_DP = 6

/** 作者主页按钮：高 92px ÷ 2.5 = 36.8dp，内边距左右 34px ÷ 2.5 = 13.6dp（`ProfileScreen.gd:231-247`）。 */
private const val AUTHOR_HOME_HEIGHT_DP = 36.8f
private const val AUTHOR_HOME_PAD_H_DP = 13.6f

/**
 * 设置页（Tab 页，`page = 3`）。移植自 Godot 版 `scripts/ui/ProfileScreen.gd`。
 *
 * **结构逐字对齐原版 `ProfileScreen.gd:50-103`**：eyebrow `SETTINGS` → 标题 `设置` →
 * `Scroll`（`Sections`，间距 30px）→ `个性化` 段（`各自独立成页，点进去细调`）下挂
 * **两行**（外观设置 / 口味偏好）→ `关于` 段（关于卡片）。原版这里**只有这两段**，
 * 音频类设置（音源 / 倍速 / 定时 / 循环 / 单次次数）在 `ProfileScreen.gd:83-85` 的注释里
 * 明确写了**已搬去播放页右上角「更多」面板**（Kotlin 对应 `PlayerPage.kt` 的播放设置弹层），
 * 所以本页不就地放任何播放开关 —— 移植时自己加的那一段（播放与外观）已按用户要求删掉。
 *
 * 版式也照原版：页头（eyebrow + 标题）**不随内容滚动**，`ScrollContainer` 只包住
 * `Sections`（`ProfileScreen.gd:70-81`），与 `LibraryScreen.kt` 同一口径。
 *
 * 免责声明照样过 `TextGuard.mask`：这条不是装饰，是原版的硬要求。
 */
@Composable
fun ProfileScreen() {
    val shell = LocalShell.current
    val c = PullTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = Dims.PAD_SCREEN.dp, end = Dims.PAD_SCREEN.dp, top = CONTENT_TOP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(CONTENT_GAP_DP.dp),
    ) {
        Text(text = "SETTINGS", color = c.textDim, fontSize = Typo.EYEBROW.sp)
        Text(
            text = "设置",
            color = c.text,
            fontSize = Typo.DISPLAY.sp,
            fontWeight = FontWeight.SemiBold,
        )

        // ---- Sections：只有这一段滚动（原版 Scroll.expand_fill + Sections 间距 30px）----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
        ) {
            // ---- 个性化（原版 `ProfileScreen.gd:87-99`，恰好两行）------------
            SectionHeader("个性化", "各自独立成页，点进去细调")
            Column(verticalArrangement = Arrangement.spacedBy(GROUP_GAP_DP.dp)) {
                SettingRow(
                    title = "外观设置",
                    hint = "封面 / 背景 / 恢复默认 / 动效速度",
                    onClick = { shell.openAppearance() },
                )
                SettingRow(
                    title = "口味偏好",
                    hint = "节奏感 / 旋律性 / 人声 · 偏好身份码",
                    onClick = { shell.openTaste() },
                )
            }

            // ---- 关于 ----------------------------------------------------------
            SectionHeader("关于")
            AboutCard()
        }
    }
}

/**
 * 关于卡片：照抄 Godot 版 `ProfileScreen.gd:194-268` 的文案与顺序 —— `Pull Up` → 副标题 →
 * 分隔线 → `作者：XFZC` → 「作者主页  ›」按钮 → 免责声明（过 `TextGuard.mask`）→ 版本行。
 *
 * 唯一的适配差异：原版版本行是 `版本 %s · %s` % [VERSION, Integrity.status_text()]，
 * Kotlin 侧**没有 Integrity（字节码 / 着色器指纹自检）这套设施**（`Integrity.gd` 校验的是
 * Godot 的 .gd 字节码与 .gdshader，Android 工程里没有对应物），所以只留版本号，
 * 不编造状态文案。
 */
@Composable
private fun AboutCard() {
    val c = PullTheme.colors
    val shell = LocalShell.current
    val shape = pullShape(Dims.RADIUS_SMALL)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.line, shape)
            .padding(horizontal = ABOUT_PAD_H_DP.dp, vertical = ABOUT_PAD_V_DP.dp),
        verticalArrangement = Arrangement.spacedBy(ABOUT_GAP_DP.dp),
    ) {
        Text("Pull Up", color = c.text, fontSize = Typo.TITLE.sp)
        Text(
            "本地优先的私人播放器 · 无账号 · 不上传数据",
            color = c.textDim,
            fontSize = Typo.META.sp,
        )
        HairLine()
        Text("作者：XFZC", color = c.text, fontSize = Typo.SUB.sp)
        AuthorHomeButton(onClick = { shell.openUrl(AUTHOR_HOMEPAGE) })
        Text(
            // 免责声明的措辞必须过 TextGuard：原版的硬要求，别绕过。
            text = TextGuard.mask(
                "免责声明：本应用仅供个人学习与自用，不提供、不存储任何音频内容；" +
                    "所有歌曲、封面与歌词均来自第三方公开接口，版权归原作者与版权方所有。" +
                    "请勿用于商业用途，如喜欢请支持正版。\n\n" +
                    "本项目由作者自费 40 元制作（接口/调试等开销），免费分享，禁止倒卖。",
            ),
            color = c.textDim,
            fontSize = Typo.META.sp,
        )
        Text("版本 $APP_VERSION", color = c.textDim, fontSize = Typo.META.sp)
    }
}

/**
 * 「作者主页  ›」按钮。移植自 `ProfileScreen.gd:231-247` 的 `home_button`：`button_box(SURFACE)`
 * （纯填充、无描边、`RADIUS_SMALL`）、左对齐、高 92px、字号 `FONT_SUB`、字色 `ACCENT_2`。
 * 之前用 `ActionButton` 把它画成了「居中 + 14sp + 次按钮描边」，与原版不符。
 */
@Composable
private fun AuthorHomeButton(onClick: () -> Unit) {
    val c = PullTheme.colors
    val shape = pullShape(Dims.RADIUS_SMALL)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AUTHOR_HOME_HEIGHT_DP.dp)
            .clip(shape)
            .background(c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = AUTHOR_HOME_PAD_H_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "作者主页  ›",
            color = c.accent2,
            fontSize = Typo.SUB.sp,
        )
    }
}
