package com.pullup.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.data.LocalPlaylist
import com.pullup.player.data.PlaylistKind
import com.pullup.player.data.Playlists
import com.pullup.player.data.TasteModel
import com.pullup.player.data.TextGuard
import com.pullup.player.data.Track
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.components.Cover
import com.pullup.player.ui.components.IconAsset
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.pullShape
import kotlinx.coroutines.launch

/** 首页日推封顶：与 Godot 版 `HomeScreen.MAX_DAILY` 一致（`HomeScreen.gd:29`）。 */
private const val MAX_DAILY = 35

// ---------------------------------------------------------------------------
// 几何（Godot px ÷ 2.5 = dp / sp）
//
// 真源是 `scripts/ui/HomeScreen.gd` 与 `scripts/ui/UIFactory.gd` 里的常量与
// offset，换算口径见 `scripts/ui/AppTheme.gd:64`「1080px = 432dp，即 1dp = 2.5px」。
// 下面写的**已经是除过 2.5 的 dp / sp**，不要再除一次。
// ---------------------------------------------------------------------------

/** 内容区顶部留白：`content.offset_top = 72.0`（`HomeScreen.gd:98`）。 */
private const val CONTENT_TOP_DP = 28.8f

/** content 与 sections 的 separation 26px（`HomeScreen.gd:100,116`）。 */
private const val SECTION_GAP_DP = 10.4f

/** Header 的 separation 14px（`HomeScreen.gd:278`）。 */
private const val HEADER_GAP_DP = 5.6f

/** 顶栏圆钮 95×95px（`HomeScreen.gd:297`）。 */
private const val HEADER_BUTTON_DP = 38

/** 搜索胶囊高 120px（`HomeScreen.gd:321`）。 */
private const val SEARCH_PILL_DP = 48

/** 搜索胶囊左右内边距 38px（`HomeScreen.gd:332-333`）。 */
private const val SEARCH_PAD_DP = 15.2f

/** 搜索胶囊内图标 52px（`HomeScreen.gd:343`）。 */
private const val SEARCH_ICON_DP = 20.8f

/** 搜索胶囊内行间距 22px（`HomeScreen.gd:338`）。 */
private const val SEARCH_GAP_DP = 8.8f

/** 大卡高 `HERO_HEIGHT = 376.0`（`UIFactory.gd:144`）。 */
private const val HERO_HEIGHT_DP = 150.4f

/** 大卡封面 `HERO_ART = 300.0`（`UIFactory.gd:145`）。 */
private const val HERO_ART_DP = 120

/** 大卡四边内边距与行间距都是 38px（`UIFactory.gd:507,512`）。 */
private const val HERO_PAD_DP = 15.2f

/** 大卡信息列 separation 14px（`UIFactory.gd:521`）。 */
private const val HERO_INFO_GAP_DP = 5.6f

/** 大卡主按钮高 103px（`UIFactory.gd:534`）。 */
private const val HERO_ACTION_DP = 41.2f

/** 大卡右上角紫光斑：460×460px，相对右上角 offset (160, −140)（`UIFactory.gd:484-487`）。 */
private const val HERO_BLOB_DP = 184
private const val HERO_BLOB_X_DP = 64
private const val HERO_BLOB_Y_DP = -56f

/** 动作按钮高 120px（`HomeScreen.gd:212`）、两颗之间 separation 20px（`HomeScreen.gd:168`）。 */
private const val ACTION_HEIGHT_DP = 48
private const val ACTION_GAP_DP = 8

/**
 * 歌单格子：封面 `PLAYLIST_TILE_COVER = 476.0`、footer `PLAYLIST_TILE_FOOTER = 170.0`
 * （`UIFactory.gd:127,132`），四边内边距与列内 separation 都是 12px（`UIFactory.gd:279,284`）。
 */
private const val TILE_WIDTH_DP = 190.4f
private const val TILE_HEIGHT_DP = 258.4f
private const val TILE_PAD_DP = 4.8f

/** 格子封面 `cover − 24 = 452px`（`UIFactory.gd:287`）。 */
private const val TILE_ART_DP = 181

/** 格子圆角 `TILE_RADIUS = 14`（`UIFactory.gd:129`）。 */
private const val TILE_RADIUS = 6

/** 横滑架最小高 = 476 + 170 + 20（`HomeScreen.gd:194-196`）；架子列距 20px（`:202`）。 */
private const val SHELF_MIN_HEIGHT_DP = 266.4f
private const val SHELF_GAP_DP = 8

/** 「⋯」删除钮 120×120px，位置 (476 − 120 − 12, 12)（`HomeScreen.gd:456-457`）。 */
private const val DELETE_SIZE_DP = 48
private const val DELETE_X_DP = 137.6f
private const val DELETE_Y_DP = 4.8f

/** 私密角标位置 (476 − 148, 30)（`UIFactory.gd:235`）。 */
private const val BADGE_X_DP = 131.2f
private const val BADGE_Y_DP = 12f

/** 提示文字最小高 140px（`UIFactory.gd:552`）。 */
private const val MESSAGE_MIN_HEIGHT_DP = 56

/** `UIFactory._make_text()` 给 Label 钉的行高倍率（`UIFactory.gd:770`）。 */
private const val LINE_HEIGHT_RATIO = 1.45f

/**
 * 首页 Tab。1:1 移植自 `scripts/ui/HomeScreen.gd`，逐块顺序照抄 `_build()` / `_build_playlists()`：
 *
 * 1 Header（♪ + Pull Up + 搜索圆钮，`:275-313`）
 * 2 Scroll 容器（`:105-111`）
 * 3 eyebrow `YOUR SOUND, YOUR SPACE`（`:120`）
 * 4 问候语 `今晚，听点什么？`（`:124-129`）
 * 5 搜索胶囊（`:317-355`）
 * 6 「今日推荐歌单」大卡（`:403-420` → `UIFactory.make_playlist_hero`）
 * 7 加载态标签（`:140-146`）与隐藏的 DailyHeader / DailyList（`:153-161`）
 * 8 `刷新歌单` / `新建歌单` 两颗整宽按钮（`:165-179, 207-218`）
 * 9 `我的歌单` 小节头（`:181-187` → `UIFactory.make_section_header("我的歌单","","新建歌单")`）
 * 10 横滑歌单架（`:189-203, 425-440`）
 *
 * 两条铁律：「我的收藏」永远排在最前（`:429`）；「⋯」只出现在用户自建歌单上（`:435, 444-445`）。
 *
 * 原版 `content.offset_bottom = -366`（`:99`）是在给迷你播放条 + 底栏让位；Compose 侧由
 * `PullUpRoot.kt:199-210` 把首页放进 `Box(weight(1f))` 之上，等价，这里不再重复留白。
 */
@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors
    val scope = rememberCoroutineScope()

    val playlists by app.playlists.playlists.collectAsStateWithLifecycle()
    val favorites by app.favorites.songs.collectAsStateWithLifecycle()
    val blacklist by app.blacklist.entries.collectAsStateWithLifecycle()

    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("加载中…") }
    var heroMessage by remember { mutableStateOf<String?>(null) }
    var raw by remember { mutableStateOf<List<Track>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<LocalPlaylist?>(null) }

    /**
     * 对应 `_show_loading(text)` + `APIHandler.get_recommend()`（`HomeScreen.gd:84-85, 495-497`）：
     * 首次进入是 `加载中…`，点刷新是 `正在刷新歌单…`。
     */
    suspend fun load(statusText: String) {
        loading = true
        status = statusText
        app.api.getRecommend().fold(
            onSuccess = { songs ->
                if (songs.isEmpty()) {
                    // 已经落过盘的歌单还在，就别用错误文案把它顶掉（`HomeScreen.gd:369`）
                    if (!app.playlists.hasDaily()) heroMessage = "每日推荐加载失败：没有拿到数据"
                } else {
                    // 日推排序＝口味模型（节奏 / 旋律 / 人声 × 语种 × 标签），分高者在前
                    raw = TasteModel.rankSongs(songs, app.preferences)
                    heroMessage = null
                }
            },
            onFailure = { e ->
                if (!app.playlists.hasDaily()) {
                    heroMessage = "每日推荐加载失败：${e.message ?: "未知错误"}"
                }
            },
        )
        // 请求回来即隐藏（`_hide_loading()`，`HomeScreen.gd:363`）
        loading = false
    }

    LaunchedEffect(Unit) { load("加载中…") }

    // 黑名单一变就地重排（不再打接口）；全被挡住时给个说法，否则下面就是一片空。
    LaunchedEffect(raw, blacklist) {
        if (raw.isEmpty()) return@LaunchedEffect
        val filtered = app.blacklist.filterSongs(raw)
        if (filtered.isEmpty()) {
            heroMessage = "日推都被黑名单挡住了\n（长按歌曲可以调整）"
            return@LaunchedEffect
        }
        app.playlists.ensureDaily(filtered.take(MAX_DAILY))
        heroMessage = null
    }

    val daily = playlists.firstOrNull {
        it.id == Playlists.DAILY_ID && it.songs.isNotEmpty()
    }

    // 「我的歌单」架：最前面固定一张「我的收藏」，其后是用户自建歌单（`HomeScreen.gd:429-430`）。
    // 今日推荐歌单不在这条架上 —— 它已经是首页大卡。
    val shelf = buildList {
        add(app.playlists.favoritesPlaylist())
        favorites.size // 收藏一变就重铺（封面取收藏第一首）
        addAll(playlists.filter { it.kind == PlaylistKind.USER })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dims.PAD_SCREEN.dp)
            .padding(top = CONTENT_TOP_DP.dp),
    ) {
        // 1 Header
        HomeHeader(onSearch = { shell.goSearch() })

        Spacer(Modifier.height(SECTION_GAP_DP.dp))

        // 2 Scroll 容器（原版横向禁用、只竖向滚）
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP_DP.dp),
        ) {
            // 3 eyebrow（无字距 —— `UIFactory.make_eyebrow` 只设字号与颜色）
            Text(text = "YOUR SOUND, YOUR SPACE", color = c.textDim, fontSize = Typo.EYEBROW.sp)

            // 4 问候语
            Text(text = "今晚，听点什么？", color = c.text, fontSize = Typo.DISPLAY.sp, fontWeight = FontWeight.SemiBold)

            // 5 搜索胶囊
            SearchField(hint = "搜索歌曲、歌手或专辑", onClick = { shell.goSearch() })

            // 6 大卡：`_hero` 里要么是歌单卡，要么是一条居中提示（`HomeScreen.gd:366-382, 403-420`）
            val message = heroMessage
            if (message != null) {
                HomeMessage(message)
            } else if (daily != null) {
                HeroCard(
                    playlist = daily,
                    onOpen = { shell.openPlaylist(daily.id) },
                    onPlay = {
                        val songs = app.blacklist.filterSongs(daily.songs)
                        if (songs.isNotEmpty()) {
                            app.playback.playSong(songs[0], songs)
                            shell.openPlayer()
                        }
                    },
                    onLongPress = {
                        daily.songs.firstOrNull()?.let { shell.showSongActions(it) }
                    },
                )
            }

            // 7 加载态标签（`_loading`，居中、FONT_BODY / TEXT_DIM、最小高 140px）。
            // 它在大卡**下面**，所以「有落盘日推 + 正在拉取」时两者同时在场（原版行为）。
            if (loading) HomeMessage(status)

            // 原版在其后还建了 `DailyHeader`（`每日推荐` / `最多 35 首`）与 `DailyList` 格子，
            // 但两个容器都 `visible = false`（`HomeScreen.gd:153-161`）——它们不参与布局，
            // 只是给 `--black` 自检探针计数用的，渲染出来没有任何视觉效果，故此处不建节点。

            // 8 刷新 / 新建：**上下两颗整宽纯文字按钮**，不是并排（`HomeScreen.gd:165-179`）
            Column(verticalArrangement = Arrangement.spacedBy(ACTION_GAP_DP.dp)) {
                HomeActionButton("刷新歌单") { scope.launch { load("正在刷新歌单…") } }
                HomeActionButton("新建歌单") { shell.openEditor() }
            }

            // 9 我的歌单小节头：等价于 make_section_header("我的歌单", "", "新建歌单")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "我的歌单",
                    color = c.text,
                    fontSize = Typo.BODY.sp,
                    lineHeight = (Typo.BODY * LINE_HEIGHT_RATIO).sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "新建歌单 ›",
                    color = c.accent2,
                    fontSize = Typo.SUB.sp,
                    modifier = Modifier.clickable { shell.openEditor() },
                )
            }

            // 10 横滑歌单架（原版是 HBoxContainer 放进横向 ScrollContainer，不是懒加载列表）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SHELF_MIN_HEIGHT_DP.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SHELF_GAP_DP.dp),
            ) {
                shelf.forEach { entry ->
                    ShelfTile(
                        entry = entry,
                        cover = app.playlists.coverPath(entry.id).ifEmpty {
                            entry.songs.firstOrNull()?.cover ?: entry.cover
                        },
                        onOpen = { shell.openPlaylist(entry.id) },
                        onDelete = { pendingDelete = entry },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        // 文案里的标点与颜文字逐字来自 ConfirmSheet.gd，别顺手改「规范」了。
        // 步数也照抄 ConfirmSheet.ask_delete()：普通歌单弹 1 次；私密歌单连弹 3 次，
        // 第 3 次换成 TEXT_LAST 那组（用户 m00675 ④：删私密歌单得多按两下）。
        val isPrivate = target.isPrivate
        val steps = remember(target.id, isPrivate) {
            if (isPrivate) listOf(
                ConfirmStep("确认删掉我吗，｡ﾟ(ﾟ´Д`ﾟ)ﾟ｡", "是", "我点错了"),
                ConfirmStep("确认删掉我吗，｡ﾟ(ﾟ´Д`ﾟ)ﾟ｡", "是", "我点错了"),
                ConfirmStep("还会见面吗(｡•́︿•̀｡)", "是...", "我后悔了！"),
            ) else listOf(
                ConfirmStep("确认删掉我吗，｡ﾟ(ﾟ´Д`ﾟ)ﾟ｡", "是", "我点错了"),
            )
        }
        var step by remember(target.id) { mutableStateOf(0) }

        PullConfirmDialog(
            message = steps[step].message,
            confirmText = steps[step].confirm,
            cancelText = steps[step].cancel,
            onConfirm = {
                // 走完才真的删；中途换文案换按钮，面板不收起（原版行为）
                if (step < steps.lastIndex) {
                    step += 1
                } else {
                    app.playlists.remove(target.id)
                    pendingDelete = null
                }
            },
            onCancel = { pendingDelete = null },
        )
    }
}

/** 确认弹窗的一步：文案 + 两颗按钮的字。 */
internal data class ConfirmStep(val message: String, val confirm: String, val cancel: String)

/**
 * `UIFactory.make_message()`（`UIFactory.gd:547-555`）：**居中、无卡片底、无描边**的纯文字，
 * 最小高 140px = 56dp，色 TEXT_DIM，字号 FONT_BODY。
 */
@Composable
private fun HomeMessage(text: String) {
    val c = PullTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MESSAGE_MIN_HEIGHT_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = c.textDim, fontSize = Typo.BODY.sp, textAlign = TextAlign.Center)
    }
}

/**
 * 品牌头（`HomeScreen.gd:275-313`）：`♪` FONT_TITLE / ACCENT → `Pull Up` FONT_TITLE / TEXT
 * 撑满剩余宽度 → 95×95 圆钮 `button_box(SURFACE)`，内部放大镜 `Color(ICON, 0.72)`，间距 14px。
 */
@Composable
private fun HomeHeader(onSearch: () -> Unit) {
    val c = PullTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = "♪", color = c.accent, fontSize = Typo.TITLE.sp)
        Spacer(Modifier.width(HEADER_GAP_DP.dp))
        Text(
            text = "Pull Up",
            color = c.text,
            fontSize = Typo.TITLE.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(HEADER_BUTTON_DP.dp)
                .clip(CircleShape)
                .background(c.surface)
                .clickable(onClick = onSearch)
                .semantics { contentDescription = "搜索" },
            contentAlignment = Alignment.Center,
        ) {
            IconAsset(
                name = "search",
                color = c.icon.copy(alpha = 0.72f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 搜索入口（`HomeScreen.gd:317-355`）。**圆角矩形，不是胶囊** —— `AppTheme.field_box()`
 * 明确写着「不要用 pill_box 做搜索框」，用户 m03174 也嫌弃过搜索框的大圆角。
 */
@Composable
private fun SearchField(hint: String, onClick: () -> Unit) {
    val c = PullTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SEARCH_PILL_DP.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(c.surface)
            .border(1.dp, c.line, pullShape(Dims.RADIUS_SMALL))
            .clickable(onClick = onClick)
            .padding(horizontal = SEARCH_PAD_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAsset(
            name = "search",
            color = c.icon.copy(alpha = 0.55f),
            modifier = Modifier.size(SEARCH_ICON_DP.dp),
        )
        Spacer(Modifier.width(SEARCH_GAP_DP.dp))
        Text(text = hint, color = c.textDim, fontSize = Typo.SUB.sp)
    }
}

/**
 * 整宽文字按钮（`HomeScreen.gd:207-218`）：**只设 text、没有图标**，
 * 高 120px = 48dp，FONT_SUB，底色 `button_box(SURFACE)`（只有填充、**没有描边**）。
 */
@Composable
private fun HomeActionButton(text: String, onClick: () -> Unit) {
    val c = PullTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ACTION_HEIGHT_DP.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(c.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = c.text, fontSize = Typo.SUB.sp)
    }
}

/**
 * 「今日推荐歌单」大卡，移植自 `UIFactory._make_feature_card()`（`UIFactory.gd:443-544`）。
 *
 * - 高 `HERO_HEIGHT = 376px` = 150.4dp，`card_box(RADIUS)`（CARD 底 + 发丝线）。
 * - 底上再叠一层 135° 斜向渐变（SURFACE_HI → SURFACE），右上角一团 20% 紫光斑。
 * - 内边距 38px、行间距 38px；左封面 `HERO_ART = 300px` = 120dp，圆角 `ART_RADIUS = 10px`。
 * - 右侧信息列：kicker FONT_META / ACCENT_2、标题 FONT_TITLE / TEXT、副标题 FONT_SUB / TEXT_DIM，
 *   再往下是整宽主按钮（高 103px、ACCENT 底、字色 ON_ACCENT）。
 *
 * 封面口径（用户 m00675 ①）：优先歌单里第一首歌的封面，为空才退回歌单自己的封面
 * （`UIFactory.hero_cover_url`，`UIFactory.gd:412-420`）。
 * **点卡片＝进歌单；点卡内「▶  立即聆听」＝把整个歌单排进队列**。
 */
@Composable
private fun HeroCard(
    playlist: LocalPlaylist,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
) {
    val c = PullTheme.colors
    val coverUrl = playlist.songs.firstOrNull()?.cover?.takeIf { it.isNotBlank() } ?: playlist.cover
    // 副标题 = "N 首" (+ 创建者) (+ 私密)，用 " · " 拼（`UIFactory.gd:424-433`）
    val count = if (playlist.trackCount > 0) playlist.trackCount else playlist.songs.size
    val subtitle = buildList {
        add("$count 首")
        if (playlist.creator.isNotBlank()) add(playlist.creator)
        if (playlist.isPrivate) add("私密")
    }.joinToString(" · ")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HERO_HEIGHT_DP.dp)
            .clip(pullShape(Dims.RADIUS))
            .background(c.card)
            .border(1.dp, c.line, pullShape(Dims.RADIUS))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        // 斜向渐变底（`UIFactory.gd:463-479`）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(c.surfaceHi, c.surface))),
        )
        // 右上角一团模糊紫光（`UIFactory.gd:481-505`）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = HERO_BLOB_X_DP.dp, y = HERO_BLOB_Y_DP.dp)
                .size(HERO_BLOB_DP.dp)
                .background(
                    Brush.radialGradient(
                        listOf(c.violet.copy(alpha = 0.20f), Color.Transparent),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(HERO_PAD_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HERO_PAD_DP.dp),
        ) {
            Cover(url = coverUrl, size = HERO_ART_DP, corner = 4)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(HERO_INFO_GAP_DP.dp),
            ) {
                Text(
                    text = "PULSE SELECT · 今日推荐歌单",
                    color = c.accent2,
                    fontSize = Typo.META.sp,
                    lineHeight = (Typo.META * LINE_HEIGHT_RATIO).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = TextGuard.mask(playlist.name),
                    color = c.text,
                    fontSize = Typo.TITLE.sp,
                    lineHeight = (Typo.TITLE * LINE_HEIGHT_RATIO).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = c.textDim,
                    fontSize = Typo.SUB.sp,
                    lineHeight = (Typo.SUB * LINE_HEIGHT_RATIO).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HERO_ACTION_DP.dp)
                        .clip(pullShape(Dims.RADIUS_SMALL))
                        .background(c.accent)
                        .clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    // 「▶」后面是**两个空格**（`UIFactory.gd:439` 逐字）
                    Text(text = "▶  立即聆听", color = c.onAccent, fontSize = Typo.SUB.sp)
                }
            }
        }
    }
}

/**
 * 「我的歌单」架上的一格，移植自 `UIFactory._make_tile()`（`UIFactory.gd:261-305`）。
 *
 * 封面 476px = 190.4dp、格子高 476 + 170 = 646px = 258.4dp、四边内边距与列内间距 12px = 4.8dp、
 * 封面 `cover − 24 = 452px`、圆角 `TILE_RADIUS = 14px`。
 * 副标题含创建者（`UIFactory.gd:215-221`），私密歌单再挂一个「私密」角标。
 *
 * 只有 `kind == user` 的自建歌单带「⋯」删除入口；「我的收藏」既删不掉也不该有入口
 * （`HomeScreen.gd:434-436, 443-445`）。
 */
@Composable
private fun ShelfTile(
    entry: LocalPlaylist,
    cover: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = PullTheme.colors
    val count = if (entry.trackCount > 0) entry.trackCount else entry.songs.size
    val meta = buildString {
        append("$count 首")
        if (entry.creator.isNotBlank()) append(" · ").append(entry.creator)
        if (entry.isPrivate) append(" · 私密")
    }

    Box(
        modifier = Modifier
            .width(TILE_WIDTH_DP.dp)
            .height(TILE_HEIGHT_DP.dp)
            .clip(pullShape(TILE_RADIUS))
            .clickable(onClick = onOpen),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(TILE_PAD_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TILE_PAD_DP.dp),
        ) {
            Cover(url = cover, size = TILE_ART_DP, corner = TILE_RADIUS)
            Text(
                text = TextGuard.mask(entry.name),
                color = c.text,
                fontSize = Typo.SUB.sp,
                lineHeight = (Typo.SUB * LINE_HEIGHT_RATIO).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                color = c.textDim,
                fontSize = Typo.META.sp,
                lineHeight = (Typo.META * LINE_HEIGHT_RATIO).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 私密角标（`UIFactory.gd:231-243`）：位置 (476 − 148, 30)px，BG@0.82 底 + 内边距 12px
        if (entry.isPrivate) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = BADGE_X_DP.dp, y = BADGE_Y_DP.dp)
                    .clip(pullShape(Dims.RADIUS_SMALL))
                    .background(c.bg.copy(alpha = 0.82f))
                    .padding(TILE_PAD_DP.dp),
            ) {
                Text(text = "私密", color = c.accent2, fontSize = Typo.EYEBROW.sp)
            }
        }

        // 「⋯」删除入口（`HomeScreen.gd:451-465`）：120×120px = 48dp，位置 (476 − 120 − 12, 12)，
        // BG@0.72 底、字 FONT_TITLE / TEXT，`button_box` 无描边。
        //
        // 语义标签「删除歌单」是给无障碍读屏用的：原版那个按钮的可见内容只有一个「⋯」，
        // 读屏念出来毫无意义（原版靠 `button.name = "DeletePlaylist"` 标识，Android 无此概念）。
        if (entry.kind == PlaylistKind.USER) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = DELETE_X_DP.dp, y = DELETE_Y_DP.dp)
                    .size(DELETE_SIZE_DP.dp)
                    .clip(pullShape(Dims.RADIUS_SMALL))
                    .background(c.bg.copy(alpha = 0.72f))
                    .clickable(onClick = onDelete)
                    .semantics { contentDescription = "删除歌单" },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "⋯", color = c.text, fontSize = Typo.TITLE.sp)
            }
        }
    }
}

/**
 * 确认弹窗。移植自 `scripts/ui/ConfirmSheet.gd` —— 文案里的标点与颜文字不要改。
 *
 * 点遮罩等于「取消」，绝不等于「是」。
 */
@Composable
internal fun PullConfirmDialog(
    message: String,
    confirmText: String,
    cancelText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = PullTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.scrim)
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .clip(pullShape(Dims.RADIUS))
                .background(c.bg)
                .border(1.dp, c.line, pullShape(Dims.RADIUS))
                .clickable(enabled = false) {}
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                color = c.text,
                fontSize = Typo.BODY.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(pullShape(Dims.RADIUS_SMALL))
                        .background(c.surface)
                        .border(1.dp, c.line, pullShape(Dims.RADIUS_SMALL))
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = cancelText, color = c.text, fontSize = Typo.SUB.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(pullShape(Dims.RADIUS_SMALL))
                        .background(c.accent)
                        .clickable(onClick = onConfirm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = confirmText, color = c.onAccent, fontSize = Typo.SUB.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
