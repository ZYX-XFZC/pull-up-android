package com.pullup.player.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.pullup.player.data.Preferences
import com.pullup.player.data.Quality
import com.pullup.player.data.Track
import com.pullup.player.player.PlaybackController
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.components.IconAsset
import com.pullup.player.ui.components.blurredBackdrop
import com.pullup.player.ui.components.dominantColor
import com.pullup.player.ui.components.dominantOfBitmap
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.Motion
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.dampEasing
import com.pullup.player.ui.theme.pullShape
import com.pullup.player.ui.theme.rememberPressScale
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 波形柱数。与 Godot 版 `PlayerPage.WAVE_BARS` 一致。 */
private const val WAVE_BARS = 45

/** 图标占按钮短边的比例（`TransportButton.gd:92`）。 */
private const val GLYPH_RATIO = 0.72f

/**
 * 全屏播放页。移植自 `scripts/ui/PlayerPage.gd`。
 *
 * 自上而下 8 块：顶栏 / 标题块 / 大封面 / 歌词槽 / 波形 / 进度区 / 控制行 /
 * 播放设置弹层（+ 音质二级菜单）。
 *
 * 几何全部是原稿 px ÷ 2.5（与 `Dims` / `Typo` 同一口径，真源 `scripts/ui/AppTheme.gd:64`）：
 * 页面左右内边距 48px = 19.2dp、上下 64px = 25.6dp、块间距 26px = 10.4dp；
 * 大封面 600px = 240dp / 圆角 28px = 11.2dp；歌词槽 168px = 67.2dp；波形 890×120px = 356×48dp；
 * 控制行间距 46px = 18.4dp（112/134/190/134/112px = 44.8/53.6/76/53.6/44.8dp）。
 *
 * 两条被用户反复强调的口径：
 * - **封面不做呼吸/缩放**，且封面框**底色完全透明** —— 圆角切掉的四角要露出页面背景，
 *   不能露出面板色（用户 m03206，`PlayerPage.gd:454-456`）；
 * - **只有播放键是胶囊**，其余按钮圆角矩形（用户 m01292）。
 */
@Composable
fun PlayerPage(onClose: () -> Unit) {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors

    val track by app.playback.current.collectAsStateWithLifecycle()
    val playing by app.playback.playing.collectAsStateWithLifecycle()
    val position by app.playback.position.collectAsStateWithLifecycle()
    val duration by app.playback.duration.collectAsStateWithLifecycle()
    val playMode by app.playback.playMode.collectAsStateWithLifecycle()
    val shuffle by app.playback.shuffle.collectAsStateWithLifecycle()
    val repeatCount by app.playback.repeatCount.collectAsStateWithLifecycle()
    val loading by app.playback.loading.collectAsStateWithLifecycle()
    val speed by app.playback.speed.collectAsStateWithLifecycle()
    val sleepLeft by app.playback.sleepLeft.collectAsStateWithLifecycle()
    val error by app.playback.error.collectAsStateWithLifecycle()
    val servedQuality by app.playback.servedQuality.collectAsStateWithLifecycle()
    val favorites by app.favorites.songs.collectAsStateWithLifecycle()
    val customArt by app.customArt.cover.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var lyricText by remember { mutableStateOf("♪ 音乐已就绪") }
    var lyricSub by remember { mutableStateOf("") }
    var lyricLines by remember { mutableStateOf<List<LrcLine>>(emptyList()) }
    var seeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }
    var lastLineIndex by remember { mutableStateOf(-1) }
    // 音质菜单定位：原版是「算死位置贴在音质按钮正上方」（`PlayerPage.gd:827-842`），
    // 所以要把按钮与页面根节点在窗口里的位置都量下来。
    var rootOriginPx by remember { mutableStateOf(Offset.Zero) }
    var rootWidthPx by remember { mutableIntStateOf(0) }
    var qualityButtonPx by remember { mutableStateOf(Offset.Zero) }
    /** 大封面在窗口里的矩形（点歌词槽时带给歌词页做飞入补间）。 */
    var coverRectInWindow by remember { mutableStateOf<Rect?>(null) }

    val isFavorite = track?.let { t -> favorites.any { it.id == t.id } } == true

    // 切歌淡入（`PlayerPage.gd:930-941`）：封面 0.45 / 标题 0.40 / 歌手 0.55，四条轨道各走各的。
    // **只做透明度，不做缩放** —— 缩放是呼吸的事，而封面明确不许呼吸。
    val coverAlpha = remember { Animatable(1f) }
    val titleAlpha = remember { Animatable(1f) }
    val artistAlpha = remember { Animatable(1f) }
    val coverFadeMs = PullTheme.state.anim(Motion.ANIM_SONG_COVER)
    val titleFadeMs = PullTheme.state.anim(Motion.ANIM_SONG_TITLE)
    val artistFadeMs = PullTheme.state.anim(Motion.ANIM_SONG_ARTIST)
    LaunchedEffect(track?.id) {
        if (track == null) return@LaunchedEffect
        coverAlpha.snapTo(0f)
        titleAlpha.snapTo(0f)
        artistAlpha.snapTo(0f)
        launch { coverAlpha.animateTo(1f, tween(coverFadeMs)) }
        launch { titleAlpha.animateTo(1f, tween(titleFadeMs)) }
        launch { artistAlpha.animateTo(1f, tween(artistFadeMs)) }
    }

    /*
     * 「当前音质」显示什么：**以服务端实际下发的档位为准**。
     *
     * 用户选的档位只是请求，实测无版权凭证时 lossless 及以上一律被静默降级回 exhigh；
     * 所以这里分三种情况如实显示，绝不把「我选了无损」当成「我在听无损」：
     *   1. 还没解析出来 → 显示所选档位（未实现的那些自带「（未实现）」标记）；
     *   2. 解析出来了、与请求一致 → 显示该档位；
     *   3. 解析出来了、但比请求低 → 显示实际档位 + 括号里点明哪一档拿不到。
     */
    val qualityText = run {
        val requestedIndex = app.preferences.bitrateIndex()
        val servedLabel = Quality.servedLabel(servedQuality)
        if (servedLabel.isEmpty()) {
            app.preferences.bitrateLabel()
        } else {
            val servedIndex = Quality.indexOf(servedQuality)
            val requestedLabel = Quality.LEVELS[requestedIndex].label
            if (servedIndex >= requestedIndex) servedLabel else "$servedLabel（$requestedLabel 拿不到）"
        }
    }

    // 歌词：按当前歌取一次。**只缓存真解析出行的那一次** —— 网络抽风不该变成永久的
    // 「暂无歌词」。取不到时最多重试一次，再不行就老老实实显示无歌词。
    LaunchedEffect(track?.id) {
        val id = track?.id ?: 0L
        lyricText = "♪ 音乐已就绪"
        lyricSub = ""
        lyricLines = emptyList()
        lastLineIndex = -1
        if (id <= 0) {
            lyricText = "♪ 暂无歌词"
            return@LaunchedEffect
        }

        cachedLyrics[id]?.let { text ->
            val parsed = parseLrc(text)
            if (parsed.isNotEmpty()) {
                lyricLines = parsed
                return@LaunchedEffect
            }
        }

        lyricText = "♪ 歌词加载中…"
        var attempt = 0
        while (attempt < 2) {
            val raw = app.api.getLyric(id).getOrNull()?.first.orEmpty()
            val parsed = parseLrc(raw)
            if (parsed.isNotEmpty()) {
                cachedLyrics[id] = raw
                lyricLines = parsed
                lastLineIndex = -1
                return@LaunchedEffect
            }
            attempt++
            if (attempt < 2) delay(1100)
        }
        // 解析不出行就是「暂无歌词」——原版没有为本地文件单独写一句文案
        // （`PlayerPage.gd:1027/1033` 与 `_apply_lyric` 只有这两句）。
        lyricText = "♪ 暂无歌词"
    }

    // 高亮行：**只在行号真的变了才改文本** —— 每 250ms 无条件重写会让文本节点不停重排。
    LaunchedEffect(lyricLines, position) {
        if (lyricLines.isEmpty()) return@LaunchedEffect
        val idx = lineIndexAt(lyricLines, position / 1000f)
        if (idx == lastLineIndex) return@LaunchedEffect
        lastLineIndex = idx
        if (idx < 0) {
            lyricText = "♪"
            lyricSub = ""
        } else {
            lyricText = lyricLines[idx].text
            lyricSub = ""
        }
    }

    // 拖动结束后才真正 seek；拖动中用本地值，听用户的。
    // 没在拖时进度条平滑跟随（原版 `Motion.damp(..., 14.0)`，`PlayerPage.gd:260-265`）——
    // 位置每 250ms 才来一次，直接贴上去会一格一格跳。
    //
    // 曲线用 `dampEasing(14.0, 250)`：原版是每帧推进一次的临界阻尼弹簧递推
    // （`Motion.gd:51-58`），它的连续解就是 `1 - (1+ωt)·e^(-ωt)`，`dampEasing` 正是这条
    // 按 250ms 归一化后的 Easing。原先这里用 `LinearEasing`，匀速追上去的手感和原版不同。
    val rawFraction = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
    val followedFraction by animateFloatAsState(
        targetValue = rawFraction,
        animationSpec = tween(PROGRESS_FOLLOW_MS, easing = progressFollowEasing),
        label = "progressFollow",
    )
    val fraction = if (seeking) seekFraction else followedFraction

    // 大封面当前该显示哪张图：自定义封面优先，其次歌曲封面（`PlayerPage.gd:1141-1145`）。
    val coverUrl = if (customArt != null) app.customArt.coverPath() else track?.cover.orEmpty()

    // 背景两层（`PlayerPage.gd:294-313`，加在所有内容之前）：
    //   _bg      —— 当前封面的真模糊版，铺满、初始透明；
    //   _bg_dim  —— 压暗层，初始 Color(BG, 0.80)，换封面时染成封面主色。
    // 自定义背景存在时**不再拿封面做模糊**，直接用自定义背景那张
    // （`PlayerPage.gd:1146, 1158`）。
    val customBg by app.customArt.background.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var backdrop by remember { mutableStateOf<Bitmap?>(null) }
    var backdropTint by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(customBg, coverUrl) {
        val bg = customBg
        if (bg != null) {
            backdrop = bg
            backdropTint = dominantOfBitmap(bg, DOMINANT_DARKEN)
            return@LaunchedEffect
        }
        if (coverUrl.isBlank()) {
            backdrop = null
            backdropTint = null
            return@LaunchedEffect
        }
        backdrop = blurredBackdrop(context, coverUrl)
        backdropTint = dominantColor(context, coverUrl, DOMINANT_DARKEN)
    }
    // 浅色模式下主色往画布色混（`PlayerPage.gd:1175-1177`）。
    val dark = PullTheme.state.isDark
    val dimColor = (backdropTint ?: c.bg).let { if (dark) it else lerp(it, c.bg, 0.78f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg.copy(alpha = 0.98f))
            .onGloballyPositioned {
                rootOriginPx = it.positionInWindow()
                rootWidthPx = it.size.width
            },
    ) {
        // 背景：模糊封面（0.40 不透明度、Crop 铺满）+ 主色压暗层 0.80。
        // 模糊背景最终不透明度是 **0.40**、压暗层是 **0.80**（`PlayerPage.gd:1163-1164, 1199-1203`）。
        backdrop?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = BACKDROP_ALPHA,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(dimColor.copy(alpha = BG_DIM_ALPHA)))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PAGE_PAD_H_DP.dp)
                .padding(top = PAGE_PAD_V_DP.dp, bottom = PAGE_PAD_V_DP.dp),
            verticalArrangement = Arrangement.spacedBy(BLOCK_GAP_DP.dp),
        ) {
            // ---- 1 顶栏（间距 20px = 8dp，`PlayerPage.gd:352-368`）----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TOP_GAP_DP.dp),
            ) {
                TopRoundButton("chevron_down", "收起播放页", onClose)
                Text(
                    text = "正在播放",
                    color = c.textDim,
                    fontSize = Typo.EYEBROW.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                TopRoundButton("more", "播放设置", { showSettings = !showSettings })
            }

            // ---- 2 标题块（间距 10px = 4dp，`PlayerPage.gd:397-425`）----
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(TITLE_GAP_DP.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = track?.name ?: "未在播放",
                    color = c.text,
                    fontSize = Typo.DISPLAY.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer { alpha = titleAlpha.value },
                )
                Text(
                    // 歌手名是可点按钮（点它进歌手页，`PlayerPage.gd:409-421`）；
                    // 未播放时它是「选择一首歌开始播放」，点了不做事。
                    text = track?.artist?.takeIf { it.isNotBlank() } ?: "选择一首歌开始播放",
                    color = c.textDim,
                    fontSize = Typo.SUB.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .graphicsLayer { alpha = artistAlpha.value }
                        .clickable(enabled = track != null) {
                            track?.let { shell.openArtist(it) }
                        },
                )
            }

            // ---- 3 大封面（无呼吸、无缩放；框底色透明）----
            // `PlayerPage.gd:428-477`：CenterContainer 里 600×600 的圆角框，
            // 底下是 DefaultArt 画的默认封面，上面盖真实封面（Crop 填满）。
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(COVER_DP.dp)
                        .graphicsLayer { alpha = coverAlpha.value }
                        .clip(pullShape(COVER_RADIUS_DP))
                        // 记下大封面在窗口里的矩形：点歌词槽时带给歌词页，做「封面飞上去」的补间
                        // （原版 `_on_lyric_input` emit `_cover_panel.get_global_rect()`，
                        // `PlayerPage.gd:317-320`）。
                        .onGloballyPositioned { coverRectInWindow = it.boundsInWindow() },
                    contentAlignment = Alignment.Center,
                ) {
                    DefaultCoverArt(COVER_RADIUS_DP, Modifier.fillMaxSize())
                    if (coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // ---- 4 歌词槽（固定高，换行不会挤走封面）----
            // `PlayerPage.gd:166-200`：LyricSlot 固定 168px + clip_contents，
            // 槽里 LyricBox 垂直居中、separation 8px，主行最多 2 行、译文行常驻。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LYRIC_SLOT_DP.dp)
                    // 原版 LyricSlot 是 `clip_contents = true`（`PlayerPage.gd:168`），
                    // 超长歌词在这里被裁掉，不会顶出槽位去挤封面。
                    .clipToBounds()
                    .clickable { shell.openLyrics(coverRectInWindow) },
                verticalArrangement = Arrangement.spacedBy(LYRIC_GAP_DP.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (error != null) error.orEmpty() else lyricText,
                    color = c.textDim,
                    fontSize = Typo.BODY.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 译文行**常驻**（原版 `_lyric_sub` 是一直在树上的空 Label，`PlayerPage.gd:194-200`）。
                Text(
                    text = lyricSub,
                    color = c.textDim,
                    fontSize = Typo.META.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ---- 5 波形（890×120px，45 根柱）----
            WaveForm(
                playing = playing && !loading,
                progress = fraction,
                modifier = Modifier.width(WAVE_WIDTH_DP.dp).height(WAVE_HEIGHT_DP.dp),
            )

            // ---- 6 进度区（`PlayerPage.gd:500-563`）----
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PROGRESS_GAP_DP.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ACTIONS_GAP_DP.dp),
                ) {
                    // 收藏钮放在进度条**上方靠左**，不放控制行 —— 会挤得播放键不居中
                    // （`PlayerPage.gd:504-509, 604`）。
                    // 已收藏 = 强调色实心心形；未收藏 = 次要色空心（`PlayerPage.gd:1006-1011`）。
                    Box(
                        modifier = Modifier
                            .size(FAV_BUTTON_DP.dp)
                            .clip(CircleShape)
                            .clickable { track?.let { app.favorites.toggle(it) } }
                            .semantics { contentDescription = if (isFavorite) "取消收藏" else "收藏" },
                        contentAlignment = Alignment.Center,
                    ) {
                        IconAsset(
                            name = if (isFavorite) "heart_filled" else "heart",
                            color = if (isFavorite) c.accent else c.textDim,
                            modifier = Modifier.size((FAV_BUTTON_DP * GLYPH_RATIO).dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .height(QUALITY_BUTTON_H_DP.dp)
                            .clip(pullShape(Dims.RADIUS_SMALL))
                            .background(c.surface)
                            .border(1.dp, c.line, pullShape(Dims.RADIUS_SMALL))
                            .onGloballyPositioned { qualityButtonPx = it.positionInWindow() }
                            .clickable { showQuality = !showQuality }
                            .padding(horizontal = CHIP_PAD_DP.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            // 显示**服务端实际下发**的档位（解析回包的 level 字段），而不是用户选的那一档。
                            // 无版权歌曲请求 lossless 也只会得到 exhigh，如实显示才算「有实际功能」。
                            text = "当前音质：$qualityText",
                            color = c.text,
                            fontSize = Typo.META.sp,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 播放队列入口（用户 m06483/m06486「播放列表在哪？」）：贴在这一行的右端。
                    // 放这里而不是控制行 —— 控制行可用宽 984px，现有 5 颗已经占 866px，再塞一颗
                    // 会挤得播放键不再居中（`PlayerPage.gd:550-554` 的注释记的就是这个坑）。
                    // 原版 `_make_transport(TransportButton.Shape.QUEUE, 120, _open_queue)`：
                    // 120px = 48dp、flat（不套深色圆）。
                    QueueButton(onClick = { shell.openQueue() })
                }

                PlayerSlider(
                    value = fraction.coerceIn(0f, 1f),
                    onChange = {
                        seeking = true
                        seekFraction = it
                    },
                    onCommit = {
                        seeking = false
                        app.playback.seek((seekFraction * duration).toLong())
                    },
                    modifier = Modifier.fillMaxWidth().height(PROGRESS_H_DP.dp),
                )

                Row(Modifier.fillMaxWidth()) {
                    val shown = if (seeking) (seekFraction * duration).toLong() else position
                    Text(text = fmtTime(shown), color = c.textDim, fontSize = Typo.META.sp)
                    Spacer(Modifier.weight(1f))
                    Text(text = fmtTime(duration), color = c.textDim, fontSize = Typo.META.sp)
                }
            }

            // ---- 7 控制行（间距 46px = 18.4dp，严格左到右；`PlayerPage.gd:598-631`）----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CONTROLS_GAP_DP.dp, Alignment.CenterHorizontally),
            ) {
                TransportIcon(
                    icon = if (shuffle) "shuffle" else "shuffle_off",
                    description = "随机播放",
                    tint = if (shuffle) c.accent else c.text,
                    size = SHUFFLE_BUTTON_DP,
                    onClick = { app.playback.toggleShuffle() },
                )
                TransportIcon(
                    icon = "prev",
                    description = "上一首",
                    tint = c.text,
                    size = SKIP_BUTTON_DP,
                    onClick = { app.playback.playPrevious() },
                )
                // 唯一胶囊主按钮（用户 m01292）：190px = 76dp 正方 ⇒ 胶囊圆角在这里就是正圆。
                // 按压同样缩到 0.88 再回弹（`TransportButton.gd:41-57`，主播放键就是 `Shape.PLAY`
                // 的 TransportButton，`PlayerPage.gd:628`）；涟漪关掉，只留缩放。
                val playInteraction = remember { MutableInteractionSource() }
                val playPressScale = rememberPressScale(playInteraction)
                Box(
                    modifier = Modifier
                        .size(PLAY_BUTTON_DP.dp)
                        .scale(playPressScale)
                        .clip(pullShape(Dims.RADIUS_PILL))
                        .background(c.accent)
                        .clickable(
                            interactionSource = playInteraction,
                            indication = null,
                            onClick = { app.playback.togglePlayPause() },
                        )
                        .semantics { contentDescription = if (playing) "暂停" else "播放" },
                    contentAlignment = Alignment.Center,
                ) {
                    val showPlay = !(playing && !loading)
                    IconAsset(
                        name = if (showPlay) "play" else "pause",
                        color = c.onAccent,
                        // 光学居中补偿（`TransportButton.gd:75-77`）：直角三角形几何居中时
                        // 看起来偏左，播放三角整体右移 4% 图标边长；暂停是对称竖条，不补。
                        modifier = Modifier
                            .size((PLAY_BUTTON_DP * GLYPH_RATIO).dp)
                            .offset(x = if (showPlay) PLAY_OPTICAL_NUDGE_DP.dp else 0.dp),
                    )
                }
                TransportIcon(
                    icon = "next",
                    description = "下一首",
                    tint = c.text,
                    size = SKIP_BUTTON_DP,
                    onClick = { app.playback.playNext() },
                )
                TransportIcon(
                    // 三态各有各的图标（用户 m01003 ⑤，`PlayerPage.gd:974-980`）。
                    icon = when (playMode) {
                        PlaybackController.SINGLE_LOOP -> "repeat_one"
                        PlaybackController.ONCE -> "repeat_off"
                        else -> "repeat"
                    },
                    description = "循环模式",
                    tint = if (playMode != PlaybackController.LIST_LOOP) c.accent else c.text,
                    size = SHUFFLE_BUTTON_DP,
                    onClick = { app.playback.cyclePlayMode() },
                )
            }
        }

        // ---- 8 播放设置弹层（右上角，632×? px：left −680 / top 180 / right −48）----
        val popupScale by animateFloatAsState(
            targetValue = if (showSettings) 1f else 0.96f,
            animationSpec = tween(
                if (showSettings) VIEW_IN_MS else VIEW_OUT_MS,
                easing = FastOutSlowInEasing,
            ),
            label = "settingsScale",
        )
        AnimatedVisibility(
            visible = showSettings,
            enter = fadeIn(tween(VIEW_IN_MS)),
            // 原版没有退场补间：`_toggle_popup` 关闭时直接 `visible = false`（`PlayerPage.gd:898-900`）。
            exit = ExitTransition.None,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = POPUP_TOP_DP.dp, end = POPUP_END_DP.dp),
        ) {
            SettingsPopup(
                playMode = playMode,
                repeatCount = repeatCount,
                speed = speed,
                sleepLeft = sleepLeft,
                modifier = Modifier.graphicsLayer {
                    scaleX = popupScale
                    scaleY = popupScale
                    // 缩放支点在右上角（`PlayerPage.gd:904`）。
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                },
                onStepCount = { app.playback.setRepeatCount(repeatCount + it) },
                onCycleSpeed = {
                    val i = Preferences.SPEED_STEPS.indexOfFirst { abs(it - speed) < 0.001f }
                    val next = Preferences.SPEED_STEPS[(i + 1).mod(Preferences.SPEED_STEPS.size)]
                    app.playback.setSpeed(next)
                    app.preferences.setSpeed(next)
                },
                onCycleSleep = {
                    val steps = Preferences.SLEEP_STEPS
                    val cur = sleepLeft
                    var index = 0
                    for (k in steps.indices) {
                        if (steps[k] == cur) {
                            index = k
                            break
                        }
                    }
                    app.playback.startSleepTimer(steps[(index + 1) % steps.size])
                },
            )
        }

        // ---- 9 音质二级菜单（贴在音质按钮正上方，原版算死位置）----
        if (showQuality) {
            val density = LocalDensity.current
            val menuHeightDp = QUALITY_ROW_DP * Quality.LEVELS.size +
                QUALITY_GAP_DP * (Quality.LEVELS.size - 1) + QUALITY_PAD_DP * 2
            val menuHeightPx = with(density) { menuHeightDp.dp.toPx() }
            val gapPx = with(density) { QUALITY_MENU_GAP_DP.dp.toPx() }
            val padPx = with(density) { Dims.PAD_SCREEN.dp.toPx() }
            val menuWidthPx = with(density) { QUALITY_MENU_W_DP.dp.toPx() }
            val left = (qualityButtonPx.x - rootOriginPx.x)
                .coerceIn(padPx, (rootWidthPx - menuWidthPx - padPx).coerceAtLeast(padPx))
            val top = (qualityButtonPx.y - rootOriginPx.y - menuHeightPx - gapPx).coerceAtLeast(padPx)
            Box(
                Modifier
                    .offset(
                        x = with(density) { left.toDp() },
                        y = with(density) { top.toDp() },
                    ),
            ) {
                QualityMenu(
                    current = app.preferences.bitrateIndex(),
                    onPick = { index ->
                        showQuality = false
                        if (index != app.preferences.bitrateIndex()) {
                            app.preferences.setBitrateIndex(index)
                            app.playback.reloadQuality()
                        }
                    },
                )
            }
        }
    }
}

// ---- 页面几何（原稿 px ÷ 2.5 → dp / sp，逐项注明出处）----------------------

/** 页面左右内边距 48px = 19.2dp、上下 64px = 25.6dp、块间距 26px = 10.4dp（`PlayerPage.gd:150-158`）。 */
private const val PAGE_PAD_H_DP = 19.2f
private const val PAGE_PAD_V_DP = 25.6f
private const val BLOCK_GAP_DP = 10.4f

/** 顶栏间距 20px = 8dp（`PlayerPage.gd:355`）。 */
private const val TOP_GAP_DP = 8f

/** 标题块间距 10px = 4dp（`PlayerPage.gd:399`）。 */
private const val TITLE_GAP_DP = 4f

/** 大封面 600px = 240dp、圆角 28px = 11.2dp（`PlayerPage.gd:60-62`）。 */
private const val COVER_DP = 240
private const val COVER_RADIUS_DP = 11

/** 歌词槽 168px = 67.2dp、槽内行距 8px = 3.2dp（`PlayerPage.gd:65, 177`）。 */
private const val LYRIC_SLOT_DP = 67.2f
private const val LYRIC_GAP_DP = 3.2f

/** 波形画布 890×120px = 356×48dp（`PlayerPage.gd:493`）。 */
private const val WAVE_WIDTH_DP = 356
private const val WAVE_HEIGHT_DP = 48

/** 进度区：外层 separation 6px = 2.4dp、动作行 separation 24px = 9.6dp（`PlayerPage.gd:502, 507`）。 */
private const val PROGRESS_GAP_DP = 2.4f
private const val ACTIONS_GAP_DP = 9.6f

/** 收藏钮 132px = 52.8dp（`PlayerPage.gd:508`）。 */
private const val FAV_BUTTON_DP = 52.8f

/** 音质按钮：高 92px = 36.8dp、内边距左右 40px = 16dp（`PlayerPage.gd:515, 390-393`）。 */
private const val QUALITY_BUTTON_H_DP = 36.8f
private const val CHIP_PAD_DP = 16f

/** 进度条的平滑跟随时长：位置回调节奏（`PlaybackController.kt:76 TICK_MS = 250L`）。 */
private const val PROGRESS_FOLLOW_MS = 250

/**
 * 进度条跟随的收敛速度（原版 `PlayerPage.gd:272` `Motion.damp(..., 14.0, delta)`）。
 * 配 [PROGRESS_FOLLOW_MS] 得到 ωT = 3.5，正好在 250ms 内收敛掉 97%。
 */
private const val PROGRESS_FOLLOW_RATE = 14f

/** 进度条跟随曲线：临界阻尼阶跃响应（`ui/theme/MotionCurves.kt` 的 `dampEasing`）。 */
private val progressFollowEasing = dampEasing(PROGRESS_FOLLOW_RATE, PROGRESS_FOLLOW_MS)

/** 进度条：控件最小高 60px = 24dp、轨道 12px = 4.8dp、旋钮 30px = 12dp（`PlayerPage.gd:545, 566-595`）。 */
private const val PROGRESS_H_DP = 24f
private const val TRACK_DP = 4.8f
private const val KNOB_DP = 12f

/** 控制行间距 46px = 18.4dp；112/134/190px = 44.8/53.6/76dp（`PlayerPage.gd:602-630`）。 */
private const val CONTROLS_GAP_DP = 18.4f
private const val SHUFFLE_BUTTON_DP = 44.8f
private const val SKIP_BUTTON_DP = 53.6f
private const val PLAY_BUTTON_DP = 76f

/** 播放三角右移 4% 图标边长 = 0.04 × (190×0.72)px = 5.47px = 2.19dp（`TransportButton.gd:75-77`）。 */
private const val PLAY_OPTICAL_NUDGE_DP = 2.19f

/** 顶栏圆钮 118px = 47.2dp（`PlayerPage.gd:373`）。 */
private const val TOP_BUTTON_DP = 47.2f

/** 播放队列入口钮 120px = 48dp（`PlayerPage.gd:555`）；图标半宽占短边 0.34（`TransportButton.gd:27`）。 */
private const val QUEUE_BUTTON_DP = 48f
private const val QUEUE_GLYPH_RATIO = 0.34f

/** 设置弹层：left −680 / top 180 / right −48 ⇒ 宽 632px = 252.8dp、距顶 72dp、距右 19.2dp（`PlayerPage.gd:640-646`）。 */
private const val POPUP_W_DP = 252.8f
private const val POPUP_TOP_DP = 72f
private const val POPUP_END_DP = 19.2f
private const val POPUP_PAD_H_DP = 10.4f
private const val POPUP_PAD_V_DP = 8.8f
private const val POPUP_GAP_DP = 4.8f

/** 设置行：高 92px = 36.8dp、左右内边距 24px = 9.6dp（`PlayerPage.gd:701, 712-713`）。 */
private const val ROW_H_DP = 36.8f
private const val ROW_PAD_DP = 9.6f

/** 循环次数：步进钮 76px = 30.4dp、数值宽 90px = 36dp（`PlayerPage.gd:673, 887`）。 */
private const val STEP_DP = 30.4f
private const val COUNT_W_DP = 36f

/** 音质菜单：宽 460px = 184dp、行高 88px = 35.2dp、内边距 18px = 7.2dp、行距 10px = 4dp（`PlayerPage.gd:67-70`）。 */
private const val QUALITY_MENU_W_DP = 184f
private const val QUALITY_ROW_DP = 35.2f
private const val QUALITY_PAD_DP = 7.2f
private const val QUALITY_GAP_DP = 4f
private const val QUALITY_MENU_GAP_DP = 6.4f

/** 弹层/菜单的入场时长：`ANIM_VIEW_IN` / `ANIM_VIEW_OUT`（`AppTheme.gd:87-88`）。 */
private val VIEW_IN_MS = (Motion.ANIM_VIEW_IN * 1000f).toInt()
private val VIEW_OUT_MS = (Motion.ANIM_VIEW_OUT * 1000f).toInt()

/**
 * 背景两层的不透明度（`PlayerPage.gd:1163-1164, 1199-1203`，注释说是「对齐首页环境光」的像素实测值）：
 * 模糊封面最终淡到 **0.40**（不是 1.0），压暗层 **0.80**、主色压暗系数 **0.88**。
 * 原来的 0.74/0.74 会把整屏抬成亮灰（首页空白像素 21–32，播放页曾 56–62）。
 */
private const val BACKDROP_ALPHA = 0.40f
private const val BG_DIM_ALPHA = 0.80f
private const val DOMINANT_DARKEN = 0.88f

/**
 * 默认专辑封面。复刻 `scripts/ui/DefaultArt.gd`：圆角底 + 压暗层 + 唱片 + 内描边。
 *
 * 原版没有真实封面时就是它顶着（`PlayerPage.gd:461-465`）。**全部代码绘制**，不引外部图片。
 * 注意 `draw_style_box(box, full)` 在 Godot 里并不受 `content_margin` 影响 —— 所以
 * `DefaultArt.gd:36-38` 那道「下半」实际是整块压暗层，这里按实际观感照画。
 */
@Composable
private fun DefaultCoverArt(radius: Int, modifier: Modifier = Modifier) {
    val c = PullTheme.colors
    Canvas(modifier) {
        val s = minOf(size.width, size.height)
        if (s <= 4f) return@Canvas
        val full = Size(size.width, size.height)
        val corner = CornerRadius(radius.dp.toPx(), radius.dp.toPx())

        // 1) 底：圆角 stylebox（圆角外保持透明）+ 压暗层
        drawRoundRect(color = c.surfaceHi, size = full, cornerRadius = corner)
        drawRoundRect(color = c.bg.copy(alpha = 0.55f), size = full, cornerRadius = corner)

        // 2) 唱片：外圈 + 5 道纹路 + 中心
        val center = Offset(size.width * 0.5f, size.height * (0.5f - 0.04f))
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

        // 3) 内描边（同样走圆角）
        drawRoundRect(
            color = c.icon.copy(alpha = 0.07f),
            size = full,
            cornerRadius = corner,
            style = Stroke(width = maxOf(s * 0.006f, 1f)),
        )
    }
}

/**
 * 简单波形：45 根柱，已播段着强调色。
 *
 * 柱高照原版着色器（`PlayerPage.gd:24-58`）：`gain = 0.45 + 0.55*|sin(i*5.99)|`，
 * **暂停时所有柱子一样高**（`h = base_h = 26px`，不做静态正弦起伏）；播放时才叠加
 * `|sin(wave_time*1.9 + i*0.55)| * amp(90px) * gain`。已播段 = `i/bars <= progress`
 * 且**仅在播放时**着色。**封面不做呼吸，波形照旧动** —— 原版就是这个分工。
 */
@Composable
private fun WaveForm(playing: Boolean, progress: Float, modifier: Modifier = Modifier) {
    val c = PullTheme.colors
    var waveTime by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) waveTime += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }

    Canvas(modifier) {
        val bars = WAVE_BARS
        val cell = size.width / bars
        val barW = cell * 0.505f
        val baseH = size.height * (26f / 120f)
        val amp = size.height * (90f / 120f)
        val t = waveTime
        for (i in 0 until bars) {
            val gain = 0.45f + 0.55f * abs(sin(i * 5.99f))
            val h = if (playing) {
                baseH + abs(sin(t * 1.9f + i * 0.55f)) * amp * gain
            } else {
                baseH
            }
            val lit = playing && i.toFloat() / bars <= progress
            val x = cell * (i + 0.5f)
            drawLine(
                color = if (lit) c.accent else c.textDim.copy(alpha = if (playing) 0.55f else 0.30f),
                start = Offset(x, size.height / 2 - h / 2),
                end = Offset(x, size.height / 2 + h / 2),
                strokeWidth = barW,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 播放页进度条（`PlayerPage.gd:542-595`）：**12px 圆头粗轨 + 30px 圆形旋钮**。
 *
 * 自绘而不用 Material3 `Slider`：M3 默认轨道 4dp、旋钮 20dp、末端还有一颗停靠点圆点，
 * 与原版「粗轨 + 大旋钮」的观感差得远。颜色照原版：轨道 `ICON@0.16`、已播段 `ACCENT`、
 * 旋钮 `ACCENT_HI`。
 */
@Composable
private fun PlayerSlider(
    value: Float,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PullTheme.colors
    var widthPx by remember { mutableIntStateOf(1) }
    Box(
        modifier = modifier
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { onChange((it.x / widthPx).coerceIn(0f, 1f)) },
                    onDragEnd = { onCommit() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onChange((change.position.x / widthPx).coerceIn(0f, 1f))
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onChange((offset.x / widthPx).coerceIn(0f, 1f))
                    onCommit()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val trackH = TRACK_DP.dp.toPx()
            val corner = CornerRadius(trackH / 2f, trackH / 2f)
            drawRoundRect(
                color = c.icon.copy(alpha = 0.16f),
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = corner,
            )
            val w = size.width * value.coerceIn(0f, 1f)
            if (w > 0f) {
                drawRoundRect(
                    color = c.accent,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(w, trackH),
                    cornerRadius = corner,
                )
            }
            // 旋钮中心像原版一样留出半个旋钮的边距，不会溢出控件（Godot 的 grabber_area 同理）。
            val knobR = KNOB_DP.dp.toPx() / 2f
            drawCircle(
                color = c.accentHi,
                radius = knobR,
                center = Offset(w.coerceIn(knobR, size.width - knobR), cy),
            )
        }
    }
}

/**
 * 次级控制钮：只画图标、不套深色圆（`flat = true`，套上会显得笨重）。图标占短边 0.72。
 *
 * 按压缩放（`TransportButton.gd:41-57`）：原版这些钮全是 `TransportButton` 实例，
 * 按下缩到 0.88、松开回弹 —— 所以这里也走 [rememberPressScale]，
 * 并且用 `indication = null` 关掉 Material 涟漪（原版 `flat` 钮没有涟漪层，
 * 两种反馈同时出现会互相打架）。
 */
@Composable
private fun TransportIcon(
    icon: String,
    description: String,
    tint: Color,
    size: Float,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interaction)
    Box(
        modifier = Modifier
            .size(size.dp)
            .scale(pressScale)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        IconAsset(
            name = icon,
            color = tint,
            modifier = Modifier.size((size * GLYPH_RATIO).dp),
        )
    }
}

/** 顶栏图标钮：`button_box(SURFACE)`，图标占短边 0.72（`PlayerPage.gd:372-380`）。
 *
 * 形状**不是正圆**：`PlayerPage.gd:383` 用的是 `AppTheme.button_box()`，而它返回
 * `panel_box(color, RADIUS_SMALL)` —— `RADIUS_SMALL = 32` px ⇒ 32÷2.5 = **12.8dp 圆角方块**。
 * 原版函数名叫 `_make_round_button`（`:381`），但「round」指的是 96px 级别的圆底，
 * 落到画布上就是圆角矩形；全站「胶囊只留给播放键」的规矩也是这条（用户 m01292）。
 *
 * 按压缩放：`_make_round_button` 内部建的是 `TransportButton`（`PlayerPage.gd:341-342`），
 * 所以和传输钮一样缩到 0.88 再回弹；涟漪关掉只留缩放。
 */
@Composable
private fun TopRoundButton(icon: String, description: String, onClick: () -> Unit) {
    val c = PullTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interaction)
    Box(
        modifier = Modifier
            .size(TOP_BUTTON_DP.dp)
            .scale(pressScale)
            .clip(RoundedCornerShape(TOP_BUTTON_RADIUS_DP.dp))
            .background(c.surface)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        IconAsset(
            name = icon,
            color = c.text,
            modifier = Modifier.size((TOP_BUTTON_DP * GLYPH_RATIO).dp),
        )
    }
}

/** 顶栏图标钮的圆角：`AppTheme.RADIUS_SMALL = 32` px ÷ 2.5 = 12.8dp（`AppTheme.gd:78`）。 */
private const val TOP_BUTTON_RADIUS_DP = 12.8f

/**
 * 播放队列入口钮（原版 `TransportButton.Shape.QUEUE`，`TransportButton.gd:143-152 _draw_queue()`）。
 *
 * **手绘而不是图标集**：`_texture_name()` 不认 `Shape.QUEUE`（回空串），
 * 且 `res/drawable-nodpi/` 里本来就没有 `queue.png` —— 原版因此必须手绘，
 * 这里照抄同样的画法：三条横线 + 左侧圆点，读起来是「列表」而不是「更多」。
 *
 * 几何（原版 `_draw()` 的 `half := minf(size.x, size.y) * GLYPH_RATIO`，
 * `GLYPH_RATIO := 0.34` 是**半宽**比例）：
 * - `dot_r   = half * 0.16`
 * - `line_h  = half * 0.20`
 * - `dot_x   = center.x - half * 0.78`
 * - `line_x0 = center.x - half * 0.34`，`line_x1 = center.x + half * 0.92`
 * - 第 i 根的 `y = center.y + (i - 1) * half * 0.68`（i = 0..2）
 *
 * 按钮本身 120px = **48dp**、flat（不套深色圆）。按下换强调色的做法沿用本页其它图标钮。
 */
@Composable
private fun QueueButton(onClick: () -> Unit) {
    val c = PullTheme.colors
    Box(
        modifier = Modifier
            .size(QUEUE_BUTTON_DP.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "播放列表" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val side = minOf(size.width, size.height)
            // GLYPH_RATIO 是半宽比例（`TransportButton.gd:27`），不是图标边长比例。
            val half = side * QUEUE_GLYPH_RATIO
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f

            val dotR = half * 0.16f
            val lineH = half * 0.20f
            val dotX = cx - half * 0.78f
            val lineX0 = cx - half * 0.34f
            val lineX1 = cx + half * 0.92f

            for (i in 0..2) {
                val y = cy + (i - 1f) * half * 0.68f
                // 圆点
                drawCircle(color = c.text, radius = dotR, center = Offset(dotX, y))
                // 横线（原版 draw_rect 的 y 是以中心对齐的左上角）
                drawRect(
                    color = c.text,
                    topLeft = Offset(lineX0, y - lineH * 0.5f),
                    size = Size(lineX1 - lineX0, lineH),
                )
            }
        }
    }
}

/** 播放设置弹层（`PlayerPage.gd:634-737`）。 */
@Composable
private fun SettingsPopup(
    playMode: Int,
    repeatCount: Int,
    speed: Float,
    sleepLeft: Int,
    modifier: Modifier = Modifier,
    onStepCount: (Int) -> Unit,
    onCycleSpeed: () -> Unit,
    onCycleSleep: () -> Unit,
) {
    val c = PullTheme.colors
    Column(
        modifier = modifier
            .width(POPUP_W_DP.dp)
            .clip(pullShape(Dims.RADIUS))
            .background(c.card)
            .border(1.dp, c.line, pullShape(Dims.RADIUS))
            .padding(horizontal = POPUP_PAD_H_DP.dp, vertical = POPUP_PAD_V_DP.dp),
        verticalArrangement = Arrangement.spacedBy(POPUP_GAP_DP.dp),
    ) {
        Text(text = "播放设置", color = c.textDim, fontSize = Typo.META.sp)

        // 循环次数只在「单次播放」下露面（`PlayerPage.gd:677, 750-752`）
        if (playMode == PlaybackController.ONCE) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.8f.dp),
            ) {
                Text(text = "循环次数", color = c.text, fontSize = Typo.SUB.sp, modifier = Modifier.weight(1f))
                StepButton("－") { onStepCount(-1) }
                Text(
                    text = repeatCount.toString(),
                    color = c.accent,
                    fontSize = Typo.SUB.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(COUNT_W_DP.dp),
                )
                StepButton("＋") { onStepCount(1) }
            }
        }

        // 音源：占位行，不给「点了会变」的错觉（`PlayerPage.gd:681-683`，值「暂未实现」）
        PopupRow(title = "音源", hint = "该功能暂未实现", value = "暂未实现", enabled = false) {}
        PopupRow(title = "播放速度", hint = "变速会同时变调", value = String.format("%.2f×", speed)) { onCycleSpeed() }
        PopupRow(
            title = "定时关闭",
            hint = "到点自动停止播放",
            // 原版 `PlayerManager.sleep_minutes_left()` 返回的就是**分钟**（`PlayerPage.gd:757-758`）
            value = if (sleepLeft <= 0) "关" else "$sleepLeft 分钟",
        ) { onCycleSleep() }
    }
}

/** 步进钮：主题默认按钮 76×76px = 30.4dp、底色 `SURFACE`（`PlayerPage.gd:883-889`）。 */
@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    val c = PullTheme.colors
    Box(
        modifier = Modifier
            .size(STEP_DP.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(c.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = c.text, fontSize = Typo.SUB.sp)
    }
}

/**
 * 弹层里的一行（`PlayerPage.gd:698-737`）：高 92px、底色 `SURFACE_HI`、左右内边距 24px。
 *
 * 左侧标题（`FONT_SUB`/`TEXT`）+ 提示（`FONT_META`/`TEXT_DIM`），右侧当前值（`FONT_SUB`/`ACCENT`）。
 * **禁用行底色不变** —— 原版给 `音源` 行覆盖的 disabled 样式仍是 `button_box(SURFACE_HI)`
 * （`PlayerPage.gd:683`），只有文字转 `TEXT_DIM`。
 */
@Composable
private fun PopupRow(title: String, hint: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = PullTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_H_DP.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(c.surfaceHi)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ROW_PAD_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.8f.dp)) {
            Text(text = title, color = if (enabled) c.text else c.textDim, fontSize = Typo.SUB.sp)
            if (hint.isNotEmpty()) {
                Text(text = hint, color = c.textDim, fontSize = Typo.META.sp, maxLines = 1)
            }
        }
        Text(text = value, color = c.accent, fontSize = Typo.SUB.sp)
    }
}

/**
 * 音质档位菜单。当前档前缀 `✓ `（`PlayerPage.gd:773-783`）。
 *
 * 服务端拿不到的档位（`lossless` 及以上，实测一律降级回 exhigh）**照用户要求列出来并标
 * 「未实现」**，而不是藏起来假装没有 —— 用户 m01767 明确要求「不能解析那就标记未实现」。
 * 点了仍然会发请求：万一哪天接口放开了，不用改代码就能用上。
 */
@Composable
private fun QualityMenu(current: Int, onPick: (Int) -> Unit) {
    val c = PullTheme.colors
    Column(
        modifier = Modifier
            .width(QUALITY_MENU_W_DP.dp)
            .clip(pullShape(Dims.RADIUS))
            .background(c.card)
            .border(1.dp, c.line, pullShape(Dims.RADIUS))
            .padding(QUALITY_PAD_DP.dp),
        verticalArrangement = Arrangement.spacedBy(QUALITY_GAP_DP.dp),
    ) {
        Quality.LEVELS.forEachIndexed { index, level ->
            val selected = index == current
            val label = if (level.implemented) level.label else "${level.label}（未实现）"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(QUALITY_ROW_DP.dp)
                    .clip(pullShape(Dims.RADIUS_SMALL))
                    .background(c.surfaceHi)
                    .clickable { onPick(index) }
                    .padding(horizontal = CHIP_PAD_DP.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = if (selected) "✓ $label" else label,
                    color = when {
                        selected -> c.accent
                        // 未实现的档位压暗，一眼能看出它现在拿不到。
                        !level.implemented -> c.textDim
                        else -> c.text
                    },
                    fontSize = Typo.SUB.sp,
                )
            }
        }
    }
}

// ---- 歌词小工具 -----------------------------------------------------------

/** 真正解析出行过的歌词才进缓存。键是歌曲 id。 */
private val cachedLyrics = HashMap<Long, String>()

/** 一行歌词：时间（秒）+ 正文。 */
internal data class LrcLine(val time: Float, val text: String)

/**
 * LRC 解析。与 Godot 版 `scripts/Lyrics.gd` 同口径：
 * 支持一行多标签 `[00:12.34][01:20.00]歌词`、毫秒 2~3 位、忽略 `[ti:]`/`[ar:]`
 * 这类跑到 `]` 后面没有正文的元信息行；结果按时间升序。
 *
 * 占位文案（「这似乎是一首纯音乐呢…」）在 [com.pullup.player.data.MusicApi.getLyric]
 * 里已经剥掉了，这里不再管。
 */
internal fun parseLrc(text: String): List<LrcLine> {
    val out = ArrayList<LrcLine>()
    for (raw in text.lineSequence()) {
        var rest = raw.trim()
        if (!rest.startsWith("[")) continue
        while (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close < 0) break
            val seconds = stampToSeconds(rest.substring(1, close))
            rest = rest.substring(close + 1)
            if (seconds >= 0f) {
                val body = rest.trim()
                if (body.isNotEmpty()) out.add(LrcLine(seconds, body))
            }
        }
    }
    out.sortBy { it.time }
    return out
}

/** `mm:ss` 或 `mm:ss.xx` → 秒；格式不对回 -1。 */
private fun stampToSeconds(stamp: String): Float {
    val parts = stamp.split(':')
    if (parts.size != 2) return -1f
    val minutes = parts[0].trim().toFloatOrNull() ?: return -1f
    val seconds = parts[1].trim().toFloatOrNull() ?: return -1f
    return minutes * 60f + seconds
}

/**
 * 当前该高亮第几行：时间 ≤ `pos + 0.05` 的最后一行，没有回 -1。
 * 与 `Lyrics.line_at()` 一致（0.05s 是原版的容差，别自己改大）。
 */
internal fun lineIndexAt(lines: List<LrcLine>, pos: Float): Int {
    var result = -1
    for (i in lines.indices) {
        if (lines[i].time <= pos + 0.05f) result = i else break
    }
    return result
}

internal fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
