package com.pullup.player.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pullup.player.data.Track
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import kotlin.math.abs
import kotlin.math.sin

/**
 * 通用组件。全部由 GDScript 版 scripts/ui/UIFactory.gd 的工厂方法移植而来。
 *
 * 造型规则（原版注释里反复强调的两条）：
 * - 卡片 = 半透明填充 + 2px 发丝描边 + 大圆角；
 * - **胶囊只留给播放键**，按钮一律圆角矩形（用户明确嫌弃过「超绝大圆角」）。
 */

/** 歌曲行圆角（原版 `UIFactory.gd:151` `ROW_RADIUS := 32` ⇒ 32÷2.5 = 12.8dp）。 */
private const val ROW_RADIUS_DP = 12.8f

/** 节标题右侧小标圆角（原版 `AppTheme.gd:596` `chip_box` 的 radius 24 ⇒ 24÷2.5 = 9.6dp）。 */
private const val SECTION_CHIP_RADIUS_DP = 9.6f

/** 节标题右侧小标内边距（原版 `AppTheme.gd:599-602` `chip_box` 的 margin 18/8 ⇒ 7.2/3.2dp）。 */
private const val SECTION_CHIP_PAD_H_DP = 7.2f
private const val SECTION_CHIP_PAD_V_DP = 3.2f

/**
 * 按钮里的图标占短边的比例：`TransportButton.gd:92` 的 `minf(size) * 0.72`
 * （图标集存在时走的正是这一支，手绘分支的 0.34 永不生效）。
 */
private const val BUTTON_ICON_RATIO = 0.72f

/**
 * 节标题：左边标题 + 右边「描边小标」提示（等价 `Card.make_section_header`，`UIFactory.gd:577-611`）。
 *
 * 原版 `make_section_header` 的右侧只有两种形态：要么是 `link_text` 的蓝色可点链接（`%s ›`），
 * 要么是 `hint` 的**描边圆角小标**（`AppTheme.chip_box()`：透明底 + 2px 描边 LINE + 圆角 24px +
 * 内边距 18/8），**不是一段飘着的裸灰字**（`UIFactory.gd:600` 注释：「不再是一段飘着的裸灰字」）。
 * 本组件只实现 hint 小标这一支；链接形态由调用方用 [trailing] 自接（如 `清空` / `新建歌单`）。
 *
 * 字号照原版：标题 `FONT_BODY`（`Typo.BODY`），小标文字 `FONT_META`（`Typo.META`）。
 */
@Composable
fun SectionHeader(title: String, hint: String = "", trailing: @Composable (() -> Unit)? = null) {
    val c = PullTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = c.text,
            fontSize = Typo.BODY.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(10.dp))
        if (hint.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(SECTION_CHIP_RADIUS_DP.dp))
                    .background(Color.Transparent)
                    .border(1.dp, c.line, RoundedCornerShape(SECTION_CHIP_RADIUS_DP.dp))
                    .padding(horizontal = SECTION_CHIP_PAD_H_DP.dp, vertical = SECTION_CHIP_PAD_V_DP.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = hint, color = c.textDim, fontSize = Typo.META.sp)
            }
        }
        trailing?.invoke()
    }
}

/** 提示卡：空态/错误态统一用它（等价 Card.make_message）。 */
@Composable
fun MessageCard(text: String, accent: Boolean = false) {
    val c = PullTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dims.RADIUS.dp))
            .background(c.card)
            .border(1.dp, c.line, RoundedCornerShape(Dims.RADIUS.dp))
            .padding(horizontal = 18.dp, vertical = 22.dp),
    ) {
        Text(
            text = text,
            color = if (accent) c.accent else c.textDim,
            fontSize = 14.sp,
        )
    }
}

/**
 * 空态：图标盘 + 标题 + 说明（+ 可选主按钮）。移植自 `Card.make_empty_state`（`UIFactory.gd:41-102`）。
 *
 * 这是原版**每个列表页都用的空态造型**，此前 Kotlin 侧一律退化成一行 `MessageCard` 文字 ——
 * 用户 m02624 说的「恢复原来的页面样式」正包括这一块。原版几何：
 * - 整块最小高 **520px→208dp**，竖向居中，内部间距 **18px→7.2dp**（`UIFactory.gd:47-48`）
 * - 图标盘 **180×180px→72dp**，`button_box(SURFACE)` 圆角矩形，里面画 [IconAsset] 染成强调色（`:53-69`）
 * - 标题 `FONT_BODY`→16.8sp 主文字色；说明 `FONT_META`→11.2sp 暗文字色、可换行（`:71-84`）
 * - 可选按钮高 **96px→38.4dp**、主按钮样式、`FONT_SUB`→12.8sp（`:86-101`）
 *
 * 传空字符串可以省略对应的那一行（原版用 `is_empty()` 判空）。
 */
@Composable
fun EmptyState(
    icon: String,
    title: String,
    hint: String,
    actionText: String = "",
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = PullTheme.colors
    Column(
        // 最小高 520px→208dp 且整块居中（`UIFactory.gd:47-48` 的
        // `custom_minimum_size` + `ALIGNMENT_CENTER`）：空态是「页面中央的一块」，
        // 不是「贴着上一行的一条」。
        modifier = modifier.fillMaxWidth().heightIn(min = 208.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // 间距 18px→7.2dp 与「整块居中」并存（`UIFactory.gd:47-48`）。
        verticalArrangement = Arrangement.spacedBy(7.2f.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(Dims.RADIUS_SMALL.dp))
                .background(c.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (icon.isNotEmpty()) {
                IconAsset(name = icon, color = c.accent, modifier = Modifier.fillMaxSize())
            }
        }
        if (title.isNotEmpty()) {
            Text(
                text = title,
                color = c.text,
                fontSize = 16.8f.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (hint.isNotEmpty()) {
            Text(
                text = hint,
                color = c.textDim,
                fontSize = 11.2f.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText.isNotEmpty() && onAction != null) {
            ActionButton(text = actionText, onClick = onAction, primary = true)
        }
    }
}

/**
 * 主/次按钮。[primary] 用强调色实底（只有关键动作配它，例如「播放全部」「创建歌单」）。
 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = PullTheme.colors
    val bg = when {
        !enabled -> c.card
        primary -> c.accent
        else -> c.surfaceHi
    }
    val fg = when {
        !enabled -> c.textDim
        primary -> c.onAccent
        else -> c.text
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Dims.RADIUS_SMALL.dp))
            .background(bg)
            .then(if (primary || !enabled) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(Dims.RADIUS_SMALL.dp)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 一行歌曲。左侧封面 + 歌名/歌手 + 右侧时长；正在播的那首整行着强调底色、歌名变强调色，
 * 并在封面右上角浮出跳动的三根小音条。
 *
 * 原版行为（`UIFactory.gd:169-184` 的 `set_row_playing`）：播放中的行 = 强调底
 * （`ACCENT_DIM`）+ 强调色标题 + 音条可见并开始跳；未播放的行 = 完全透明的底
 * （`transparent_box`，原版注释：「Rows are invisible until touched: the list reads as
 * content, not as a stack of boxes.」）。
 *
 * [onAdd] 非空时在时长右侧多画一颗「+」——点它把这首歌加进**播放列表**并按当前模式起播
 * （用户 m05292 项⑤⑥「每首歌后面加号→加入播放列表→按当前模式播放」）。它是**兄弟节点
 * 而不是覆盖层**，所以点「+」不会连带触发整行的 [onClick]（行本身是「播放这首」）。
 */
@Composable
fun SongRow(
    track: Track,
    playing: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
) {
    val c = PullTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ROW_RADIUS_DP.dp))
            .background(if (playing) c.accentDim else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 封面右上角叠音条（原版 `_make_row` 里 bars 是 button 的子节点、压在封面角上）。
        Box {
            Cover(url = track.cover, size = 48)
            if (playing) {
                PlayingBars(
                    color = c.accent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.name,
                color = if (playing) c.accent else c.text,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(track.artist)
                    if (track.album.isNotBlank()) append(" · ").append(track.album)
                },
                color = c.textDim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.duration > 0) {
            Spacer(Modifier.width(10.dp))
            Text(text = track.durationStr, color = c.textDim, fontSize = 12.sp)
        }
        if (onAdd != null) {
            Spacer(Modifier.width(10.dp))
            AddToPlaylistButton(onClick = onAdd)
        }
    }
}

/**
 * 三根上下跳动的小音条：列表里「正在播放」的动效指示器。移植自 `scripts/ui/PlayingBars.gd`。
 *
 * 纯 `_draw` 绘制，不需要任何图片资源；不播放时把 `active` 置 false 就会静止。
 * 原版常量：`BARS := 3`、`SPEED := 4.2`、静止系数 `0.28`、相位步进 `1.15`。
 *
 * 原版 `_process` 里有一条性能保护（`PlayingBars.gd:39-41`）：**不可见时不重绘** ——
 * 播放页盖住 PlayerBar 时这个指示器不该还在每帧重绘。Kotlin 侧由「只有 `playing` 为真
 * 才进入组合」天然满足：不可见就不存在，也就没有帧回调。
 */
@Composable
fun PlayingBars(color: Color, modifier: Modifier = Modifier, active: Boolean = true) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (!active) {
            time = 0f
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    Canvas(modifier = modifier) {
        val bars = 3
        // 原版：bar_width = size.x / (BARS * 2 - 1)，即 3 根柱 + 2 个等宽间隙。
        val barWidth = size.width / (bars * 2f - 1f)
        for (i in 0 until bars) {
            val phase = time * 4.2f + i * 1.15f
            val factor = if (!active) 0.28f else 0.28f + 0.72f * abs(sin(phase))
            val barHeight = size.height * factor
            drawRect(
                color = color,
                topLeft = Offset(i * barWidth * 2f, (size.height - barHeight) * 0.5f),
                size = Size(barWidth, barHeight),
            )
        }
    }
}

/**
 * 歌曲行右侧那颗「+」。做成 34dp 的可点方块（图标 20dp）而不是裸图标 —— 触摸目标太小
 * 在手机上点不中，原版所有可点元素最小也是 40px 起。
 *
 * 图标走图标集里的 `add`（`assets/icons_v2/add.png`），不是 Material 的 `Add`。
 * 读屏文案是「加入播放列表」（用户 m05292 项⑥ 的口径），不是「加入歌单」——
 * 长按菜单里那条才叫「添加到歌单」。
 */
@Composable
fun AddToPlaylistButton(onClick: () -> Unit) {
    val c = PullTheme.colors
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(Dims.RADIUS_SMALL.dp))
            .background(c.surfaceHi)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "加入播放列表" },
        contentAlignment = Alignment.Center,
    ) {
        IconAsset(
            name = "add",
            color = c.textDim,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 方形封面。空 url 时画原版的代码绘制占位图（[DefaultArt]），避免出现空洞。
 *
 * `contentScale = Crop` 是照抄原版的 `STRETCH_KEEP_ASPECT_COVERED`（`UIFactory.gd:644`、
 * `PlayerBar.gd:113`、`LyricsScreen.gd:124`、`PlayerPage.gd:471` 全用这一种）——
 * 封面长宽比与方框不一致时应当**裁切填满**而不是留白（默认的 `Fit` 会留出空白边，
 * 看起来像封面没铺满）。
 */
@Composable
fun Cover(url: String, size: Int, corner: Int = Dims.RADIUS_SMALL) {
    Box(modifier = Modifier.size(size.dp).clip(RoundedCornerShape(corner.dp))) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size.dp),
            )
        } else {
            DefaultArt(corner = corner)
        }
    }
}

/**
 * 默认专辑封面（App 自带的占位图）。移植自 `scripts/ui/DefaultArt.gd`。
 *
 * 全部**代码绘制**，不依赖任何外部图片：品牌渐变底 + 唱片纹路 + 中心音符 + 内描边。
 * 没有真实封面时出现，颜色跟随主题，换深色/浅色或换质感会自动跟着变。
 *
 * 原版注释里点名的一条（`DefaultArt.gd:31-32`）：底色必须是**圆角**而不是直角矩形 ——
 * 圆角外要保持透明，否则真实封面的圆角遮罩会把这块底色从四个角露出来（用户 m03206 报的就是这个）。
 * 所以这里整块都画在已 `clip` 过的圆角容器里。
 */
@Composable
fun DefaultArt(corner: Int = Dims.RADIUS_SMALL) {
    val c = PullTheme.colors
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val s = minOf(w, h)
        if (s <= 4f) return@Canvas

        val radius = corner.dp.toPx()

        // 1) 底：圆角面板色 + 下 45% 压一层背景色（原版 `DefaultArt.gd:33-38`）。
        drawRoundRect(color = c.surfaceHi, size = size, cornerRadius = CornerRadius(radius, radius))
        clipPath(
            path = Path().apply {
                addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(radius, radius)))
            },
        ) {
            drawRect(
                color = c.bg.copy(alpha = 0.55f),
                topLeft = Offset(0f, h * 0.55f),
                size = Size(w, h * 0.45f),
            )
        }

        // 2) 唱片：外圈 + 5 道纹路 + 中心两点。圆心略微上移，视觉更稳（`center_bias := -0.04`）。
        val center = Offset(w * 0.5f, h * 0.46f)
        val disc = s * 0.30f
        drawCircle(color = c.shade.copy(alpha = 0.28f), radius = disc, center = center)
        for (i in 0 until 5) {
            drawCircle(
                color = c.icon.copy(alpha = 0.05f),
                radius = disc * (0.62f + 0.075f * i),
                center = center,
                style = Stroke(width = maxOf(s * 0.004f, 1f)),
            )
        }
        drawCircle(color = c.accent, radius = disc * 0.34f, center = center)
        drawCircle(color = c.bg, radius = disc * 0.10f, center = center)

        // 3) 内描边（同样走圆角，`DefaultArt.gd:50-55`）。
        drawRoundRect(
            color = c.icon.copy(alpha = 0.07f),
            size = size,
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = maxOf(s * 0.006f, 1f)),
        )
    }
}

/** 歌单横滑格子。 */
@Composable
fun PlaylistTile(
    name: String,
    cover: String,
    meta: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val c = PullTheme.colors
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(Dims.RADIUS.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Cover(url = cover, size = 124, corner = Dims.RADIUS)
        Text(
            text = name,
            color = c.text,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = meta,
            color = c.textDim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 设置行最小高（原版 `ProfileScreen.gd:129` `custom_minimum_size = Vector2(0, 132)` ⇒ 132÷2.5 = 52.8dp）。 */
private const val SETTING_ROW_HEIGHT_DP = 52.8f

/** 设置行内边距（原版 `ProfileScreen.gd:143-145` margin 左右 34 / 上下 20 ⇒ 13.6/8dp）。 */
private const val SETTING_ROW_PAD_H_DP = 13.6f
private const val SETTING_ROW_PAD_V_DP = 8f

/** 设置行标题与副标题间距（原版 `ProfileScreen.gd:157` separation 6 ⇒ 2.4dp）。 */
private const val SETTING_ROW_INFO_GAP_DP = 2.4f

/** 设置行「值 ↔ ›」间距（原版 `ProfileScreen.gd:150` separation 20 ⇒ 8dp）。 */
private const val SETTING_ROW_SEPARATION_DP = 8f

/**
 * 一行设置。左侧标题 + 副标题，右侧「值（强调色）+ ›」。移植自 `ProfileScreen.gd:127-191` 的 `_make_row`。
 *
 * 原版样式：`card_box(RADIUS_SMALL)`（CARD 填充 + 2px 描边 LINE）作 normal/hover，
 * 按下时换成 `panel_box(SURFACE_HI, RADIUS_SMALL)`（无描边、更亮）；行高 132px，
 * 内边距左右 34 / 上下 20；标题 `FONT_BODY`、副标题 `FONT_META`、值 `FONT_SUB`/ACCENT、
 * 行尾 `›` 用 `FONT_TITLE`/TEXT_DIM（`ProfileScreen.gd:160-189`）。
 *
 * 之前这里被写成「无卡片底、无描边、16dp 内边距、15sp/12sp」的裸行 —— 与原版不一致，
 * 现已按原版逐字恢复。
 */
@Composable
fun SettingRow(title: String, hint: String, value: String = "", onClick: () -> Unit) {
    val c = PullTheme.colors
    val shape = RoundedCornerShape(Dims.RADIUS_SMALL.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SETTING_ROW_HEIGHT_DP.dp)
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = SETTING_ROW_PAD_H_DP.dp, vertical = SETTING_ROW_PAD_V_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SETTING_ROW_INFO_GAP_DP.dp),
        ) {
            Text(text = title, color = c.text, fontSize = Typo.BODY.sp)
            if (hint.isNotEmpty()) {
                Text(text = hint, color = c.textDim, fontSize = Typo.META.sp)
            }
        }
        if (value.isNotEmpty()) {
            Text(text = value, color = c.accent, fontSize = Typo.SUB.sp)
            Spacer(Modifier.width(SETTING_ROW_SEPARATION_DP.dp))
        }
        Text(text = "›", color = c.textDim, fontSize = Typo.TITLE.sp)
    }
}

/**
 * 滑动开关（自绘，不引 Material 的 `Switch`）。
 *
 * 用户 m05292 项①「所有单项修改的文字开关改滑动开关」。Godot 原版没有滑轨开关
 * 可抄（`AppearanceScreen.gd` 全是 `cycle_*` 文字行），所以这里按本工程既有造型
 * 语言自绘：滑轨是 `Dims.RADIUS_PILL` 胶囊，关闭态用 `track` 令牌（0.12/0.14 透明度的
 * 中性色，和播放页进度条底同一支），开启态用 `accent`；滑块是纯白圆，关闭态缩到
 * 0.86 并让位给滑轨边，开启态用 `onAccent` 描一圈边保证在亮色上看得见。
 *
 * 颜色全部走 [PullTheme] 令牌，不写死色值 —— 深/浅切换与三种面板质感都会自动跟着变。
 * 触摸目标 52×32dp，比原版可点元素最小 40px(=16dp) 大得多，不会点不中。
 */
@Composable
fun PullSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PullTheme.colors
    // 关→开 260ms：与原版 Motion.ANIM_VIEW_IN(0.30) 同量级，不抢眼但能看清滑过去。
    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "switch",
    )
    Box(
        modifier = modifier
            .size(width = SWITCH_W_DP.dp, height = SWITCH_H_DP.dp)
            .clip(RoundedCornerShape(Dims.RADIUS_PILL.dp))
            .background(c.surfaceHi)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) },
            )
            // 图标集没有开关图标，读屏语义靠这里补（与 RoundIconButton 同例）。
            .semantics { contentDescription = if (checked) "已开启" else "已关闭" }
            .padding(SWITCH_PAD_DP.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackH = size.height
            val radius = trackH / 2f
            // 关到底时滑轨仍有这一圈，避免「一块死白」看不出是开关。
            drawRoundRect(
                color = if (fraction > 0.5f) c.accent else c.track,
                cornerRadius = CornerRadius(radius, radius),
            )
            // Canvas 已被 .padding(SWITCH_PAD_DP) 内缩过，size 已是「轨道内可用区」，
            // 所以 knob 直径 = 内区高、travel = 内区宽 - knob，两端留对称 padding。
            // 之前 knob 错用了 trackH - 2*SWITCH_PAD（在高密度屏上把垂直内缩算重，
            // 滑块偏小且竖直不居中），这里统一用内区尺寸，方为对称居中。
            val knob = size.height
            val travel = size.width - knob
            val x = travel * fraction
            drawCircle(
                color = Color.White,
                radius = knob / 2f * (0.86f + 0.14f * fraction),
                center = Offset(x + knob / 2f, trackH / 2f),
            )
            if (fraction > 0.5f) {
                // 亮色滑轨上的白滑块要有一圈对比边，否则边缘糊在 accent 里。
                drawCircle(
                    color = c.onAccent.copy(alpha = 0.35f * fraction),
                    radius = knob / 2f,
                    center = Offset(x + knob / 2f, trackH / 2f),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }
}

/** 滑动开关滑轨尺寸（原版无对物，取 Material 规范同量级：52×32dp 触摸友好）。 */
private const val SWITCH_W_DP = 52f
private const val SWITCH_H_DP = 32f
private const val SWITCH_PAD_DP = 3f

/** 分隔线。 */
@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PullTheme.colors.line),
    )
}

/** 供占位页复用：整屏居中一句说明。 */
@Composable
fun PlaceholderBody(title: String, lines: List<String>) {
    val c = PullTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dims.PAD_SCREEN.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        lines.forEach { MessageCard(it) }
    }
}

/**
 * 顶栏返回圆钮：二级页统一用它。形状是圆形，底色可用 surface。
 *
 * [icon] 是**图标集里的文件名**（见 [IconAsset]），不是 Material 矢量图标 ——
 * 原版 `SubScreen.gd:149` 的返回钮就是 `TransportButton` + `Shape.CHEVRON_DOWN`，
 * 用的是 `assets/icons_v2/chevron_down.png`。用 Material 的 `KeyboardArrowDown`
 * 会让「重构」变成「重新设计」，用户 m02624 明确要求恢复原样。
 *
 * 几何照原版 `SubScreen.gd:149-153`：返回钮 `custom_minimum_size = Vector2(96, 96)`
 * ⇒ **96px ÷ 2.5 = 38.4dp**，所以 [size] 默认 38.4dp（不是整数，Compose 的 dp 支持小数）。
 * 8 个调用点都不传 size，正好全部拿到原版尺寸。
 *
 * 图标占短边 0.72（`TransportButton.gd:92` 的 `minf(size) * 0.72`），比 [IconAsset]
 * 默认的 0.52 大 —— 按钮里的图标要更满。
 */
@Composable
fun RoundIconButton(
    icon: String,
    contentDescription: String,
    onClick: () -> Unit,
    size: Float = 38.4f,
    filled: Boolean = true,
) {
    val c = PullTheme.colors
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .then(if (filled) Modifier.background(c.surface) else Modifier)
            .clickable(onClick = onClick)
            // 图标集是纯图形，没有语义标签，读屏要靠这里补（原版靠 button.name）。
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        IconAsset(
            name = icon,
            color = c.text,
            modifier = Modifier.fillMaxSize(),
            ratio = BUTTON_ICON_RATIO,
        )
    }
}
