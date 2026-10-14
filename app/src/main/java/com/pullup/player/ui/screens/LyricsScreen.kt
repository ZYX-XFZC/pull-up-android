package com.pullup.player.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.util.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.data.LyricLine
import com.pullup.player.data.LyricsParser
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.components.Cover
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.LocalPullTheme
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.pullShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 沉浸式歌词页（点播放页的歌词槽进入）。移植自 Godot 版 `scripts/ui/LyricsScreen.gd`（699 行）。
 *
 * 版式逐项对齐原版 `_build()`（`:94-171`）与 `_place_cover()`（`:266-273`）：
 * - 全屏只有一层**半透明底色**（`BG` alpha 0.94，原版 `_backdrop` 进场补间的终点）——原版
 *   **没有**全屏大封面，封面只是左上角那块 150px→60dp 的小方图，其余靠底色透出下层页面；
 * - 小封面即返回按钮（原版 `_on_cover_input` `:276-279`：左键按下就 `close_requested`）；
 * - 标题/歌手贴着封面右侧排（不是顶栏），歌名 `FONT_BODY`/`TEXT`、歌手 `FONT_META`/`TEXT_DIM`；
 * - 歌词区左右各 `PAD_SCREEN`、上 420px→168dp、下 200px→80dp —— 封面与标题就落在这块上方的留白里。
 *
 * 三条承重细节：
 * - **当前行高亮 + 自动滚**：行号来自 `LyricsParser.lineAt`。原版把高亮行滚到视口 **40%** 处
 *   （`_scroll_to` `:672-678`），并且**用户一旦碰过歌词区就暂停跟随**，等他空闲 2700ms
 *   （`FOLLOW_IDLE_MS`）自己回位（`_hold_follow` / `_process` `:647-669`）。
 * - **点行即 seek**：原版 `_on_line_pressed` 直接 `PlayerManager.seek(float(_lines[index][0]))`，
 *   这里换成毫秒。没有歌时 `PlaybackController.seek` 会自己早退，不必额外判断。
 * - **空态是「圆形图标盘 + 标题 + 说明」**（原版 `Card.make_empty_state`，`UIFactory.gd:41-84`），
 *   不是一行 `MessageCard`。
 *
 * 三处用户 m02420 指定的改动**保持不变**（与上面的移植无关）：原文在上译文在下、每句居中、
 * 删掉每行左边的时间区间列。
 *
 * 封面飞入补间（原版 `open(from_cover: Rect2)` `:190-218`）：小封面从**播放页大封面的真实矩形**
 * 补到左上角静止位（0.46s QUINT/EASE_OUT），标题与歌手跟着封面一起走；backdrop/title/artist/
 * list 依次淡入（0.32 / 0.30 延迟 0.16 / 0.30 延迟 0.20 / 0.34 延迟 0.10）。
 * 退场（原版 `close()` `:221-246`）从封面**当前**位置补回进场时那个矩形（0.38s QUINT/EASE_IN_OUT），
 * 各层再淡出。矩形无效（null / 宽高 ≈ 0）时直接落位不飞入，对应原版 `size.x > 1.0` 的兜底。
 */
@Composable
fun LyricsScreen(
    coverRect: androidx.compose.ui.geometry.Rect? = null,
    onClose: () -> Unit = {},
) {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors
    val theme = LocalPullTheme.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()

    val track by app.playback.current.collectAsStateWithLifecycle()
    val position by app.playback.position.collectAsStateWithLifecycle()

    // 原版 `_load_song()` 用 `song.get("id", 0)` 记账，回包不带 id 时靠它挡掉过期结果；
    // 这里 LaunchedEffect 的 key 就是 songId，换歌自动取消上一轮请求，等价且更省事。
    val songId = track?.id ?: 0L

    var lines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    /** 与 [lines] 同长；第 i 项是译文，空串＝这行没有译文（不占位、不建副标签）。 */
    var translations by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    // 浏览态：碰过歌词区就叫停自动跟随，空闲 2700ms 后自己回位到当前行。
    var browsing by remember { mutableStateOf(false) }
    var browseTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(songId) {
        lines = emptyList()
        translations = emptyList()
        if (songId == 0L) {
            loading = false
            return@LaunchedEffect
        }
        // 原版 `_load_song()`（`:294-297`）：请求还在路上就先显示「歌词加载中」，不判「暂无歌词」。
        loading = true
        app.api.getLyric(songId).fold(
            onSuccess = { payload ->
                val main = LyricsParser.parse(payload.first)
                lines = main
                // 原版 `_align_translations`：两边都是 LRC，少数平台差几十毫秒，给 ±0.3s 容差取最近。
                translations = alignTranslations(main, LyricsParser.parse(payload.second))
            },
            // 取不到就退成空行集 ⇒ 走「暂无歌词 / 这首歌没有提供歌词」那一支（与原版 `_on_lyric` 一致）。
            onFailure = {
                lines = emptyList()
                translations = emptyList()
            },
        )
        loading = false
    }

    val positionSeconds = position / 1000f
    val currentIndex = LyricsParser.lineAt(lines, positionSeconds)

    // ---- 封面飞入补间（原版 open/close 的 _place_cover 插值）--------------------
    // start = 播放页大封面的窗口矩形（px）；end = 左上角静止位（px）。rect 无效就不飞。
    val startPx: Rect? = remember(coverRect) {
        coverRect?.takeIf { it.width > 1f && it.height > 1f }
    }
    // 静止位与标题锚点，按 dp 常量算成 px（窗口左上角 = 歌词页根节点的原点）。
    val restLeftPx = with(density) { Dims.PAD_SCREEN.dp.toPx() }
    val restTopPx = with(density) { COVER_TOP_DP.dp.toPx() }
    val restSizePx = with(density) { SMALL_COVER_DP.dp.toPx() }
    val titleLeftPx = with(density) { TITLE_LEFT_DP.dp.toPx() }
    val titleTopPx = with(density) { TITLE_TOP_DP.dp.toPx() }
    val artistTopPx = with(density) { ARTIST_TOP_DP.dp.toPx() }

    // 0 = 停在起点矩形，1 = 停在左上角静止位。
    val fly = remember { Animatable(if (startPx != null) 0f else 1f) }
    // 各层淡入进度（1 = 完全显示）。
    val backdropA = remember { Animatable(if (startPx != null) 0f else 0.94f) }
    val titleA = remember { Animatable(if (startPx != null) 0f else 1f) }
    val artistA = remember { Animatable(if (startPx != null) 0f else 1f) }
    val listA = remember { Animatable(if (startPx != null) 0f else 1f) }

    // 进场：封面 0.46s QUINT/EASE_OUT；backdrop 0.32s；title/artist 0.30s（延迟 0.16/0.20）；
    // list 0.34s（延迟 0.10）。原版 `open()` `:209-218`。
    LaunchedEffect(startPx) {
        if (startPx == null) return@LaunchedEffect
        fly.animateTo(1f, tween(theme.anim(0.46f), easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))
        backdropA.animateTo(0.94f, tween(theme.anim(0.32f)))
        titleA.animateTo(1f, tween(theme.anim(0.30f), delayMillis = 160))
        artistA.animateTo(1f, tween(theme.anim(0.30f), delayMillis = 200))
        listA.animateTo(1f, tween(theme.anim(0.34f), delayMillis = 100))
    }

    // 当前封面矩形（px）：由 start/end 按 fly 插值。退场时从**当前**位置续补，所以读它。
    val coverLeftPx: Float
    val coverTopPx: Float
    val coverSizePx: Float
    if (startPx != null) {
        val f = fly.value
        coverLeftPx = androidx.compose.ui.util.lerp(startPx.left, restLeftPx, f)
        coverTopPx = androidx.compose.ui.util.lerp(startPx.top, restTopPx, f)
        coverSizePx = androidx.compose.ui.util.lerp(startPx.width, restSizePx, f)
    } else {
        coverLeftPx = restLeftPx
        coverTopPx = restTopPx
        coverSizePx = restSizePx
    }

    // 点小封面 = 返回（原版 `_on_cover_input` `:276-279`）。飞入途中也能点，退场从当前位置续补。
    val scope = rememberCoroutineScope()
    val closeLyrics: () -> Unit = {
        if (startPx == null) {
            onClose()
        } else {
            // 退场 0.38s QUINT/EASE_IN_OUT，终点 = 进场时那个真实矩形（原版 `close()` `:230-243`）。
            scope.launch {
                fly.animateTo(
                    0f,
                    tween(theme.anim(0.38f), easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)),
                )
                backdropA.animateTo(0f, tween(theme.anim(0.30f)))
                titleA.animateTo(0f, tween(theme.anim(0.20f)))
                artistA.animateTo(0f, tween(theme.anim(0.20f)))
                listA.animateTo(0f, tween(theme.anim(0.20f)))
                onClose()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg.copy(alpha = backdropA.value))
            // 用户碰了歌词区 ⇒ 进入浏览态。走 Initial 阶段**只观察、不消费**事件，
            // 所以 LazyColumn 照常收得到拖动（对应原版 `_hold_follow()` 在拖动中不断续期）。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) {
                            browsing = true
                            browseTick++
                        }
                    }
                }
            },
    ) {
        // 左上角小封面（飞入补间的落点，也是「点它切回」的按钮）：原版 `_rest_rect()`
        // = Rect2(Vector2(PAD_SCREEN, 96), Vector2(150, 150))，`_place_cover()` `:266-273`。
        Box(
            modifier = Modifier
                .offset(
                    x = with(density) { coverLeftPx.toDp() },
                    y = with(density) { coverTopPx.toDp() },
                )
                .size(with(density) { coverSizePx.toDp() })
                .clip(RoundedCornerShape(SMALL_COVER_RADIUS_DP.dp))
                .clickable { closeLyrics() },
        ) {
            Cover(
                url = track?.cover.orEmpty(),
                size = SMALL_COVER_DP,
                corner = SMALL_COVER_RADIUS_DP,
            )
        }

        // 标题/歌手贴着封面右侧：`_place_cover()` 里 title.x = cover.x + 150 + 34、
        // title.y = cover.y + 26；artist 在 title.x、cover.y + 92。单行 + 省略号（autowrap OFF）。
        // 飞入时它们跟着封面一起走（锚点由同一个插值矩形算出），并各自淡入。
        //
        // ⚠️ 原版的 x/y 是**绝对坐标**，Compose 的 `offset` 是相对自然位置的平移量，
        // 而 Box 子节点的自然位置就是 (0,0)，所以「绝对坐标」与「偏移量」在这里等值。
        val coverRightPx = coverLeftPx + coverSizePx
        Text(
            text = track?.name ?: "未在播放",
            color = c.text,
            fontSize = Typo.BODY.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset(
                    x = with(density) { (coverRightPx + 13.6f).toDp() },
                    y = with(density) { (coverTopPx + 10.4f).toDp() },
                )
                .fillMaxWidth()
                .padding(end = Dims.PAD_SCREEN.dp)
                .graphicsLayer { alpha = titleA.value },
        )
        Text(
            text = track?.artist.orEmpty(),
            color = c.textDim,
            fontSize = Typo.META.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset(
                    x = with(density) { (coverRightPx + 13.6f).toDp() },
                    y = with(density) { (coverTopPx + 36.8f).toDp() },
                )
                .fillMaxWidth()
                .padding(end = Dims.PAD_SCREEN.dp)
                .graphicsLayer { alpha = artistA.value },
        )

        // 歌词区：原版 `_scroll.offset_left/right = PAD_SCREEN`、`offset_top = 420`、`offset_bottom = -200`。
        if (lines.isEmpty()) {
            LyricsEmptyState(
                title = if (loading) "歌词加载中" else "暂无歌词",
                hint = if (loading) "正在取这份歌词" else "这首歌没有提供歌词",
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = listA.value }
                    .padding(
                        start = Dims.PAD_SCREEN.dp,
                        end = Dims.PAD_SCREEN.dp,
                        top = LINES_TOP_DP.dp,
                        bottom = LINES_BOTTOM_DP.dp,
                    ),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = listA.value }
                    .padding(
                        start = Dims.PAD_SCREEN.dp,
                        end = Dims.PAD_SCREEN.dp,
                        top = LINES_TOP_DP.dp,
                        bottom = LINES_BOTTOM_DP.dp,
                    ),
                // 原版 `_list` 的 separation 8px → 3dp。
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    LyricRow(
                        line = line,
                        translation = translations.getOrElse(index) { "" },
                        active = index == currentIndex,
                        onClick = {
                            // 原版 `_on_line_pressed`（`:505-512`）：结束浏览态 → seek → 高亮立刻移过去。
                            browsing = false
                            app.playback.seek((line.time * 1000f).toLong())
                        },
                    )
                }
            }
        }
    }

    // 跟随：不在浏览态时把当前行送到视口 40% 处；在浏览态就先等够 FOLLOW_IDLE_MS 再交还。
    LaunchedEffect(browsing, browseTick, currentIndex, lines.size) {
        if (browsing) {
            // 拖动中的每个事件都会让 browseTick 自增 ⇒ 计时从最后一次动作重新起算。
            delay(FOLLOW_IDLE_MS)
            browsing = false
        } else if (currentIndex >= 0 && currentIndex < lines.size) {
            // 原版 `_scroll_to`（`:672-678`）：target = row.position.y − 视口高 * 0.4，放在 40% 处而非贴顶。
            val viewport = listState.layoutInfo.viewportSize.height
            listState.animateScrollToItem(currentIndex, -(viewport * 0.4f).roundToInt())
        }
    }
}

/**
 * 空态（原版 `Card.make_empty_state`，`UIFactory.gd:41-84`）：`VBox` 居中、`separation 18px→7dp`、
 * 最小高 `520px→208dp`；里面是 180×180px→72dp 的 `button_box(SURFACE)` 图标盘（图标用强调色），
 * 再一个居中标题（`FONT_BODY`/`TEXT`）和一行居中说明（`FONT_META`/`TEXT_DIM`）。
 *
 * ⚠️ 图标盘里的图形：原版走 `IconView` 的 `album` 图标，Kotlin 侧 `Components.kt` 没有图标集，
 * 这里用同一套界面里表示「音乐」的音符 `♪` 顶上，颜色仍是 `ACCENT`。
 */
@Composable
private fun LyricsEmptyState(title: String, hint: String, modifier: Modifier = Modifier) {
    val c = PullTheme.colors
    Column(
        modifier = modifier.heightIn(min = 208.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // 原版 box.alignment = ALIGNMENT_CENTER：内容在这块最小高里也居中。
        verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(pullShape(Dims.RADIUS_SMALL))
                .background(c.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "♪", color = c.accent, fontSize = Typo.TITLE.sp)
        }
        Text(text = title, color = c.text, fontSize = Typo.BODY.sp, textAlign = TextAlign.Center)
        Text(text = hint, color = c.textDim, fontSize = Typo.META.sp, textAlign = TextAlign.Center)
    }
}

/**
 * 一行歌词。**用户 m02420 指定的三条改动保持不变**：原文在上、译文在下、每句居中，
 * 并且不再画原版左侧那条 `mm:ss - mm:ss` 时间区间列（那列一去掉，才有沉浸页该有的居中版式）。
 *
 * 其余行内度量仍按原版：行最小高 `LINE_HEIGHT = 96px→38dp`、左内边距 `10px→4dp`、
 * 主行 `FONT_BODY`/`TEXT_DIM`、译文 `FONT_META`/`TEXT_DIM@0.8`，**没有译文就不建副标签**
 * （不占位、不出现空白块）；高亮行 `FONT_TITLE`（原版 50→20sp）+ `ACCENT`。
 */
@Composable
private fun LyricRow(
    line: LyricLine,
    translation: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val c = PullTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LINE_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(Dims.RADIUS_SMALL.dp))
            .clickable(onClick = onClick)
            // ROW_PAD = 26px → 10dp（原版 `_fit_row` 的最小高口径）。
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = line.text,
            color = if (active) c.accent else c.textDim,
            fontSize = (if (active) Typo.TITLE else Typo.BODY).sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (translation.isNotEmpty()) {
            // 原版 column separation 6px → 2dp；**没有译文就不建**，那一行不占位。
            Box(Modifier.size(2.dp))
            Text(
                text = translation,
                color = c.textDim.copy(alpha = 0.8f),
                fontSize = Typo.META.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 把译文按时间戳贴到主歌词行上，移植自 Godot `LyricsScreen._align_translations`（`:340-357`）。
 * 两个数组都按时间升序，用前向游标扫一遍即可（O(n+m)）。
 */
private fun alignTranslations(main: List<LyricLine>, translated: List<LyricLine>): List<String> {
    if (translated.isEmpty()) return List(main.size) { "" }
    val out = ArrayList<String>(main.size)
    var cursor = 0
    main.forEach { m ->
        var best = ""
        var bestGap = 0.31f
        while (cursor < translated.size && translated[cursor].time < m.time - 0.3f) cursor++
        var j = cursor
        while (j < translated.size && translated[j].time <= m.time + 0.3f) {
            val gap = abs(translated[j].time - m.time)
            if (gap < bestGap) {
                bestGap = gap
                best = translated[j].text
            }
            j++
        }
        out += best
    }
    return out
}

// 全部常量都是 Godot 画布 px ÷ 2.5（原稿 1080px = 432dp，见 `AppTheme.gd:64`）。
private const val SMALL_COVER_DP = 60         // SMALL_COVER = 150.0
private const val SMALL_COVER_RADIUS_DP = 11  // 小封面圆角 28.0
private const val COVER_TOP_DP = 38           // _rest_rect().y = 96.0
private const val TITLE_LEFT_DP = 90          // PAD_SCREEN + 150 + 34
private const val TITLE_TOP_DP = 48           // 96 + 26
private const val ARTIST_TOP_DP = 75          // 96 + 92
private const val LINES_TOP_DP = 168          // offset_top = 420.0
private const val LINES_BOTTOM_DP = 80        // offset_bottom = -200.0
private const val LINE_HEIGHT_DP = 38         // LINE_HEIGHT = 96.0

/** 原版 `FOLLOW_IDLE_MS = 2700`：松手后空闲这么久才恢复自动跟随。 */
private const val FOLLOW_IDLE_MS = 2700L
