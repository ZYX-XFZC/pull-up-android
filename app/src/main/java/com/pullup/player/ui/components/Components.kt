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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.pullup.player.data.Track
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.rememberPressScale
import kotlin.math.abs
import kotlin.math.sin

/**
 * 通用组件。全部由 GDScript 版 scripts/ui/UIFactory.gd 的工厂方法移植而来。
 *
 * 造型规则（原版注释里反复强调的两条）：
 * - 卡片 = 半透明填充 + 2px 发丝描边 + 大圆角；
 * - **胶囊只留给播放键**，按钮一律圆角矩形（用户明确嫌弃过「超绝大圆角」）。
 */

/** 空态盘里图标的占比（原版 `IconView.gd:24` 的 `ratio = 0.52`）。 */
private const val EMPTY_ICON_RATIO = 0.52f

/** 歌曲行圆角（原版 `UIFactory.gd:172` `ROW_RADIUS := 32` ⇒ 32÷2.5 = 12.8dp）。 */
private const val ROW_RADIUS_DP = 12.8f

/**
 * 歌曲行**封面**的圆角（原版 `UIFactory.gd:171` `ART_RADIUS := 10` ⇒ 10÷2.5 = 4dp）。
 *
 * 别拿 [Dims.RADIUS_SMALL] 套：那是行本身的 32px。原版注释写得很直白——
 * 「参考原型的行封面 42px 用 6dp，等比到我们约 17px，但 20/22px 太胖、还会「咬」进
 * 封面内容（用户两次反馈偏圆），所以定在 10（≈8%）」。48dp 见方的图吃 13dp 圆角
 * 几乎就成了圆形，和原版观感差得最远的一处就在这。
 */
private const val ROW_ART_RADIUS_DP = 4f

/** 行内元素间距（原版 `UIFactory.gd:905` HBox `separation = 24` ⇒ 24÷2.5 = 9.6dp）。 */
private const val ROW_GAP_DP = 9.6f

/** 行内两行文字的行距（原版 `UIFactory.gd:923` VBox `separation = 8` ⇒ 8÷2.5 = 3.2dp）。 */
private const val ROW_TEXT_GAP_DP = 3.2f

/** 行左右内边距（原版 `UIFactory.gd:900` `_full_rect_margin(14)` ⇒ 14÷2.5 = 5.6dp）。 */
private const val ROW_PAD_H_DP = 5.6f

/** 行上下内边距：行高 160px 减封面 120px 再对半 ⇒ 20px÷2.5 = 8dp（`UIFactory.gd:156-157`）。 */
private const val ROW_PAD_V_DP = 8f

/** 歌名字号（原版 `UIFactory.gd:926` 用 `FONT_BODY = 42px` ⇒ 42÷2.5 = 16.8sp）。 */
private const val ROW_TITLE_SP = 16.8f

/** 歌手·专辑字号（原版 `UIFactory.gd:929` 用 `FONT_SUB = 32px` ⇒ 32÷2.5 = 12.8sp）。 */
private const val ROW_SUB_SP = 12.8f

/** 时长字号（原版 `UIFactory.gd:932` 用 `FONT_META = 28px` ⇒ 28÷2.5 = 11.2sp）。 */
private const val ROW_META_SP = 11.2f

/** 在播音条边长（原版 `UIFactory.gd:914` 名义 34px，被 `PlayingBars.gd:30` 的 `custom_minimum_size = 46` 钳到 46px ⇒ 46÷2.5 = 18.4dp）。 */
private const val ROW_BARS_DP = 18.4f

/** 音条横向外露：`UIFactory.gd:915` 的 x 起点 94px 相对封面右沿 134px ⇒ 外露 6px÷2.5 = 2.4dp。 */
private const val ROW_BARS_OVERHANG_X_DP = 2.4f

/** 音条纵向外露：`UIFactory.gd:915` 的 y 起点 12px 相对封面顶沿 20px ⇒ 外露 8px÷2.5 = 3.2dp。 */
private const val ROW_BARS_OVERHANG_Y_DP = 3.2f

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
            // 标题必须**撑满**：原版 `UIFactory.gd:779` 给标题设的是
            // `size_flags_horizontal = Control.SIZE_EXPAND_FILL`，于是右侧小标/链接被顶到
            // 最右端，整行是「左标题 · 右小标」的分栏。
            // 早先写成 `weight(1f, fill = false)`，标题只占自身宽度 ⇒ 小标紧贴标题、
            // 右半边空一片，搜索/音乐库/设置三页的小节头全都错位。
            modifier = Modifier.weight(1f),
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
                // 图标只占盘子的 52%：原版 `IconView.gd:24,32` 的 `ratio = 0.52`
                // （`side = minf(size) * ratio`）。早先 `fillMaxSize()` 让图形铺满 72dp，
                // 视觉上比原版的 ~37dp 大了近一倍，把小标题和说明都压过去了。
                IconAsset(
                    name = icon,
                    color = c.accent,
                    modifier = Modifier.size(72.dp * EMPTY_ICON_RATIO),
                )
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
 * [onAdd] 非空时在时长右侧多画一颗「+」——点它把这首歌**追加到当前播放队列末尾、不打断当前播放**
 * （原版 `AddToQueue.gd:133 add()` → `PlayerManager.gd:344 add_to_queue()`，用户 m06166 当面选定）。
 * 它是**兄弟节点而不是覆盖层**，所以点「+」不会连带触发整行的 [onClick]（行本身是「播放这首」）。
 *
 * 本地曲目（负数 id）不画这颗「+」：原版 `UIFactory.gd:193` 只在 `int(song.id) > 0` 时才挂，
 * 因为队列里的歌要能被接口解析出流地址，本地文件给不出。
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
    // ---- 触摸阈值（原版 `scripts/ui/SongRow.gd:20-22`）----
    // 原版直接写了两个常量，Kotlin 侧 `combinedClickable` 吃的是 `ViewConfiguration` 的默认值
    // （长按≈500ms、触摸容差≈8dp），两个都对不上。这里只替换这一行范围内的 ViewConfiguration，
    // 保住 combinedClickable 自带的「松手才触发」、涟漪、无障碍语义（手写 pointerInput 会全丢）。
    //
    // - `HOLD_SECONDS := 0.45` → 长按 450 毫秒
    // - `TAP_SLOP := 26.0` px ÷ 2.5 = **10.4dp** → 松手时位移超过它就说明用户在滑动，
    //   这次触摸作废（原版 `is_tap()`，`:80-81`）。同时 `_process()` 里还有一条
    //   （`SongRow.gd:54-59`，用户 m01636 提的坑）：手指一旦离开按下点超过 TAP_SLOP
    //   就**立刻取消长按计时**，否则「按住封面往下滑想滚列表」也会在 0.45 秒后弹面板。
    //   `combinedClickable` 内部按 touchSlop 判定拖动、跳出长按检测，语义等价。
    val base = LocalViewConfiguration.current
    val density = LocalDensity.current
    val touchConfig = remember(base, density) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long = SONG_ROW_HOLD_MS
            override val touchSlop: Float = SONG_ROW_SLOP_DP * density.density
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides touchConfig) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ROW_RADIUS_DP.dp))
                .background(if (playing) c.accentDim else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = ROW_PAD_H_DP.dp, vertical = ROW_PAD_V_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面右上角叠音条（原版 `UIFactory.gd:911-916`：bars 是 button 的兄弟节点，
            // 位置 `Vector2(14 + art_size - 40, 12)` —— 横向上比封面右沿外露 6px，
            // 纵向上比封面顶沿外露 8px，看着是「骑」在右上角而不是压在封面里）。
            Box {
                Cover(url = track.cover, size = 48, corner = ROW_ART_RADIUS_DP.toInt())
                if (playing) {
                    PlayingBars(
                        color = c.accent,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = ROW_BARS_OVERHANG_X_DP.dp, y = -ROW_BARS_OVERHANG_Y_DP.dp)
                            .size(ROW_BARS_DP.dp),
                    )
                }
            }
            Spacer(Modifier.width(ROW_GAP_DP.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ROW_TEXT_GAP_DP.dp),
            ) {
                Text(
                    text = track.name,
                    color = if (playing) c.accent else c.text,
                    fontSize = ROW_TITLE_SP.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(track.artist)
                        if (track.album.isNotBlank()) append(" · ").append(track.album)
                    },
                    color = c.textDim,
                    fontSize = ROW_SUB_SP.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (track.duration > 0) {
                Spacer(Modifier.width(ROW_GAP_DP.dp))
                Text(text = track.durationStr, color = c.textDim, fontSize = ROW_META_SP.sp)
            }
            // 「+」只在**有接口 id** 的歌上出现（原版 `UIFactory.gd:193`：
            // `if with_add and int(song.get("id", 0)) > 0`）。本地曲目在 Kotlin 侧是负数 id
            // （`LocalLibrary.ID_BASE = -100_000L`），点「+」也加不进播放队列 —— 原版
            // `AddToQueue.gd:135-138` 会回一句「本地歌曲加不进去」，所以这里干脆不画。
            if (onAdd != null && track.id > 0L) {
                Spacer(Modifier.width(ROW_GAP_DP.dp))
                AddToPlaylistButton(onClick = onAdd)
                // 行内右边距 5.6dp + 这 5.6dp = 11.2dp，对上原版「+」按钮的
                // `offset_right = -(ROW_PAD + BUTTON_GAP)` = -(14 + 14)px ÷ 2.5。
                Spacer(Modifier.width(ROW_PAD_H_DP.dp))
            }
        }
    }
}

/** 长按 0.45 秒（`SongRow.gd:20 HOLD_SECONDS := 0.45`）。 */
private const val SONG_ROW_HOLD_MS = 450L

/** 松手位移上限 26px ÷ 2.5 = 10.4dp（`SongRow.gd:22 TAP_SLOP := 26.0`）。 */
private const val SONG_ROW_SLOP_DP = 10.4f

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
 * 歌曲行右侧那颗「+」。边长 48dp、图标 34.56dp —— 照抄原版 `AddToQueue.gd:33`
 * `BUTTON_SIZE := 120.0`（120÷2.5）与 `TransportButton.gd:92` 的 `minf(size) * 0.72`。
 *
 * 造型也照原版：`AddToQueue.gd:77` 的 `button_box(AppTheme.SURFACE)` —— SURFACE 填充、
 * RADIUS_SMALL（32px÷2.5 = 12.8dp）圆角、**无描边**；`:74` 的字色是 `ACCENT`。
 *
 * 读屏文案沿用「加入播放列表」（＝正在播放的队列，用户 m06166 的口径），不是「加入歌单」。
 */
@Composable
fun AddToPlaylistButton(onClick: () -> Unit) {
    val c = PullTheme.colors
    Box(
        modifier = Modifier
            .size(ADD_BUTTON_DP.dp)
            .clip(RoundedCornerShape(ADD_BUTTON_RADIUS_DP.dp))
            .background(c.surface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "加入播放列表" },
        contentAlignment = Alignment.Center,
    ) {
        IconAsset(
            name = "add",
            color = c.accent,
            modifier = Modifier.size((ADD_BUTTON_DP * BUTTON_ICON_RATIO).dp),
        )
    }
}

/** 「+」按钮边长（`AddToQueue.gd:33 BUTTON_SIZE := 120.0` px ÷ 2.5）。 */
private const val ADD_BUTTON_DP = 48f

/** 「+」按钮圆角（`AppTheme.gd:580 button_box()` 的默认半径 `RADIUS_SMALL = 32` px ÷ 2.5）。 */
private const val ADD_BUTTON_RADIUS_DP = 12.8f

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
    Cover(url = url, size = size.toFloat(), corner = corner)
}

/**
 * 同上，但边长收 `Float`。
 *
 * 原版的尺寸是 px 小数（如专辑卡封面 `PLAYLIST_ART := 152.0` ⇒ 60.8dp），
 * 而 `Dp` 支持小数 —— 走 `Int` 版会把它压成 61 而偏 0.8dp。
 * 只给确实需要小数的调用方用；整数尺寸继续用上面那个重载，不必写 `48f`。
 */
@Composable
fun Cover(url: String, size: Float, corner: Int = Dims.RADIUS_SMALL) {
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

        // 1) 底：圆角面板色，再整块压一层背景色（原版 `DefaultArt.gd:33-38`）。
        //    第二层是 `panel_box(BG@0.55)` 直接 `draw_style_box(lower, full)` ——
        //    StyleBoxFlat 的 `content_margin_top` 只影响内容排布、**不影响绘制区域**，
        //    所以原版压暗的是整块，不是只压下半边。早先这里只铺了下面 45%，
        //    上 55% 保持 SURFACE_HI，整块占位图比原版亮一截（`PlayerPage.kt` 那份
        //    一直是整块，Android 内部两份 DefaultArt 因此还不一致）。
        drawRoundRect(color = c.surfaceHi, size = size, cornerRadius = CornerRadius(radius, radius))
        clipPath(
            path = Path().apply {
                addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(radius, radius)))
            },
        ) {
            drawRect(
                color = c.bg.copy(alpha = 0.55f),
                topLeft = Offset(0f, 0f),
                size = Size(w, h),
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
 * 触摸目标 48×27.2dp（原版 `SubScreen.gd:60-61` 的 120×68px），比原版可点元素最小 40px(=16dp) 大得多，不会点不中。
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

/**
 * 滑动开关滑轨尺寸：照抄原版 `scripts/ui/SubScreen.gd:60-63` 的
 * `SWITCH_W := 120.0` / `SWITCH_H := 68.0` / `SWITCH_KNOB := 56.0` / `SWITCH_PAD := 6.0`
 * （÷2.5 ⇒ 48 × 27.2 / 旋钮 22.4 / 内距 2.4dp）。
 *
 * 早先按 Material 规范写的 52×32／内距 3dp 比原版胖一圈，开关在设置、外观两页显得突出。
 */
private const val SWITCH_W_DP = 48f
private const val SWITCH_H_DP = 27.2f
private const val SWITCH_PAD_DP = 2.4f

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
 *
 * 按压缩放：这个钮的 9 个调用点全是二级页/浮层的「收起」箭头，原版那些箭头一律是
 * `TransportButton`（`SubScreen.gd:216-217`、`AlbumScreen.gd:105`、`ArtistScreen.gd:114`、
 * `PlayerPage.gd:367` 都是 `Shape.CHEVRON_DOWN`），所以按下缩到 0.88 再回弹；
 * 涟漪关掉只留缩放，避免两种反馈叠在一起。
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
    val interaction = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interaction)
    Box(
        modifier = Modifier
            .size(size.dp)
            .scale(pressScale)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .then(if (filled) Modifier.background(c.surface) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
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

/**
 * 详情页最底那条贴底动作条：播放队列入口（原版 `UIFactory.gd:438-480 _build_detail_bar()`）。
 *
 * **为什么有这个组件**：原版 `clear_player_bar()` 把 Backdrop/Margin 的底边让到 1800 给迷你
 * 播放条腾位，而 `NavGuard` 只盖最底 2000..2160 ⇒ **1800..2000 那 200px 是「让位区」，
 * 没有任何浮层底板，直接露出画布色**，用户截图圈的就是这条带子（`UIFactory.gd:424-433`）。
 * 原版往这条带子里塞了个「播放列表 · N 首 ›」入口，既填了空白，也回答同一句话里的
 * 「播放列表在哪？」。
 *
 * 几何逐项照抄（`UIFactory.gd:378-379, 454-464`）：
 * - 高 `DETAIL_BAR_H := 120` px → **48dp**（触控下限）
 * - 左右各留 `PAD_SCREEN`（16dp，走外层 padding）
 * - 圆角矩形 `RADIUS_SMALL` —— 原版 `:449` 注释「`-- --style` 只允许播放键是胶囊」
 * - 字号 `FONT_SUB`(32px)→12.8sp；常态 `SURFACE`/`TEXT`、按下 `ACCENT`/`ON_ACCENT`
 *
 * **一处有意的偏差（不照抄锚点）**：原版用 `anchor_top/bottom = 1.0` + `offset_top =
 * -(120+20)` 把它钉在**屏幕底部**（`UIFactory.gd:456-463`），因为那块空白是「让位区」露出来的。
 * Kotlin 侧的详情页在浮层里、下方没有迷你播放条，也就没有那块空白，跟着锚底会悬在列表
 * 与屏幕底之间凭空浮着。这里改为**列尾**（列表下方、随内容走），保留「高 48dp + 圆角矩形
 * + 一个点进队列页的入口」这三项用户可感知的属性。原版的 `DETAIL_BAR_BOTTOM := 20`(8dp)
 * 因此不适用，未搬。
 *
 * 文案（`UIFactory.gd:496`）：有歌时 `播放列表 · N 首  ›`，空队列时 `播放列表  ›`
 * （点了进空态页）。
 *
 * Kotlin 侧不需要原版那套 `_detail_bars` 静态数组 + 全局只连一次 `playlist_changed` 的
 * 去重（原版踩过「同静态函数 + 不同 bind 的 Callable 相等 ⇒ 重复连接报 ERROR」），
 * 这里订阅 `playback.queue` 的 StateFlow 即可。
 */
@Composable
fun DetailQueueBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = PullTheme.colors
    val queue by LocalApp.current.playback.queue.collectAsStateWithLifecycle()
    val count = queue.size
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DETAIL_QUEUE_BAR_H_DP.dp)
            .clip(RoundedCornerShape(Dims.RADIUS_SMALL.dp))
            .background(if (pressed) c.accent else c.surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // 原版 `:496`：`"播放列表 · %d 首  ›" % count if count > 0 else "播放列表  ›"`
            text = if (count > 0) "播放列表 · $count 首  ›" else "播放列表  ›",
            color = if (pressed) c.onAccent else c.text,
            fontSize = DETAIL_QUEUE_BAR_FONT_SP.sp,
        )
    }
}

/** 贴底入口条：高 120px→48dp、字号 `FONT_SUB`32px→12.8sp（`UIFactory.gd:378, 445`）。 */
private const val DETAIL_QUEUE_BAR_H_DP = 48f
private const val DETAIL_QUEUE_BAR_FONT_SP = 12.8f
