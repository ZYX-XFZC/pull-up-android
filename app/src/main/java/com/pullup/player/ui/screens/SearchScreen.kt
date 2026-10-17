package com.pullup.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.data.Album
import com.pullup.player.data.TextGuard
import com.pullup.player.data.Track
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.components.Cover
import com.pullup.player.ui.components.EmptyState
import com.pullup.player.ui.components.MessageCard
import com.pullup.player.ui.components.SectionHeader
import com.pullup.player.ui.components.SongRow
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.pullShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 输入防抖：停手 280ms 才真正发请求（原版 `_debounce.wait_time = 0.28`）。 */
private const val DEBOUNCE_MS = 280L

/**
 * 一次取多少条搜索结果（`SearchScreen.gd:14 const SEARCH_LIMIT := 100`）。
 * 原版注释：「实测 MeTing 支持100；原来写30，用户反馈『结果太少』」。
 * Kotlin 侧原先没传这个参数，吃了 `MusicApi.search` 的默认值 30，所以只出 30 条。
 */
private const val SEARCH_LIMIT = 100

/** `Content` 上边距 72px（`SearchScreen.gd:64`）⇒ 29dp；左右走 `Dims.PAD_SCREEN`，
 *  下边距原版是 −366px（给迷你条 + 底栏让位），Kotlin 侧由外壳承担，页面里不再补。 */
private const val CONTENT_TOP_DP = 29

/** `Content` 间距 22px（`SearchScreen.gd:66`）⇒ 9dp。 */
private const val CONTENT_GAP_DP = 9

/** `Results` 间距 6px（`SearchScreen.gd:115`）⇒ 2dp。 */
private const val LIST_GAP_DP = 2

/** 输入框最小高 108px（`SearchScreen.gd:82`）⇒ 43dp；
 *  主题 `LineEdit/normal` 的内边距 34 / 20px（`AppTheme.gd:655-658`）⇒ 14 / 8dp。 */
private const val FIELD_MIN_HEIGHT_DP = 43
private const val FIELD_PAD_H_DP = 14
private const val FIELD_PAD_V_DP = 8

/** 模式标签 180×84px（`SearchScreen.gd:193`）⇒ 72×34dp；`ModeTabs` 间距 14px（`:97`）⇒ 6dp。 */
private const val TAB_MIN_WIDTH_DP = 72
private const val TAB_MIN_HEIGHT_DP = 34
private const val TAB_GAP_DP = 6

/** 历史 chip 高 78px ⇒ 31dp；`✕` 钮 72×78px ⇒ 29×31dp；`HFlowContainer` h/v separation 14px ⇒ 6dp。 */
private const val CHIP_MIN_HEIGHT_DP = 31
private const val CHIP_REMOVE_WIDTH_DP = 29
private const val CHIP_GAP_DP = 6

/** `清除全部` 最小高 70px（`SearchScreen.gd:366`）⇒ 28dp。 */
private const val CLEAR_ALL_HEIGHT_DP = 28

/** 默认按钮内边距 30 / 18px（`AppTheme.gd:627-630`）⇒ 12 / 7dp。 */
private const val BUTTON_PAD_H_DP = 12
private const val BUTTON_PAD_V_DP = 7

/**
 * 专辑卡（原版 `UIFactory.gd:510-522 make_playlist_card` → `:878-885 _make_row`）：
 * 高 `PLAYLIST_HEIGHT := 192.0`（`UIFactory.gd:161`）⇒ **76.8dp**、
 * 封面 `PLAYLIST_ART := 152.0`（`UIFactory.gd:162`）⇒ **60.8dp**；
 * 行内边距 `_full_rect_margin(14)`（`UIFactory.gd:900`）⇒ **5.6dp**、
 * 行内间距 `separation 24`（`UIFactory.gd:905`）⇒ **9.6dp**。
 *
 * 注意：这里原先是 77 / 61 / 6 / 10 的取整值，且注释把行号写成了 `:140-141`/`:702`/`:707`
 * （那是另一组常量），已按实际行号改正。
 */
private const val ALBUM_ROW_MIN_HEIGHT_DP = 76.8f
private const val ALBUM_ART_DP = 60.8f
private const val ROW_PAD_DP = 5.6f
private const val ROW_INNER_GAP_DP = 9.6f

/**
 * 搜索页（从首页推入的整页，`page = 1`）。移植自 Godot 版 `scripts/ui/SearchScreen.gd`。
 *
 * 版面逐条对齐原版 `_build()`（`SearchScreen.gd:58-125`）：`Content`（左右 `PAD_SCREEN`、
 * 上 72px、间距 22px）→ eyebrow `SEARCH` → 提问 `找点什么？` → 搜索输入框（圆角矩形的
 * `field_box`、最小高 108px、聚焦时换 `ACCENT` 描边）→ 「单曲 / 专辑」模式标签 →
 * 结果滚动区（间距 6px）→ 页尾加载行 `搜索中…`。
 * **页头、输入框与模式标签不随结果滚动**：原版的 `ScrollContainer` 只包住 `_results_box`。
 *
 * 四条承重细节：
 * - **搜索框是圆角矩形，不是胶囊**。规格书（`Pulse_UI_UX_Spec.md:108`）与
 *   `AppTheme.field_box` 都写明这一点，`pill_box` 是禁止的。
 * - **防抖 + 迟到回包作废**：停手 280ms 才发请求；关键词一变就取消上一个协程，等价于原版
 *   `_search_input.text != _last_keyword` 时丢弃回包，慢请求不会覆盖新结果。
 * - **错误文案过 `TextGuard.mask`**：原版所有要上屏的接口错误都过这一层。
 * - **模式标签只重铺结果、不重新请求**（原版 `_make_mode_tab` 的 `pressed` 回调）：专辑模式
 *   按 `album` 字段就地聚合，聚合不到专辑名时给「切回单曲」的提示。
 *
 * 空态用 `ui.components` 的 `EmptyState`（复刻原版 `Card.make_empty_state` 的图标盘造型），
 * 文案逐字照抄原版；接口失败与「专辑模式下没有专辑」仍是 `MessageCard`（原版那两处就是
 * `Card.make_message`，`SearchScreen.gd:180,286`）。历史词按任务要求只存在内存里（原版是 `user://search_history.cfg` 落盘）。
 */
@Composable
fun SearchScreen() {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors
    val scope = rememberCoroutineScope()

    val current by app.playback.current.collectAsStateWithLifecycle()

    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 是否已经为当前关键词搜过一次：用来区分「还没搜」与「搜了但没结果」。 */
    var searched by remember { mutableStateOf(false) }
    /** 最后一次真正发出去的关键词（原版 `_last_keyword`）：空态文案与迟到回包守卫都用它。 */
    var lastKeyword by remember { mutableStateOf("") }
    /** 「单曲 / 专辑」模式（原版 `_album_mode`，默认单曲）。 */
    var albumMode by remember { mutableStateOf(false) }
    // 搜索历史落盘（原版 `search_history.cfg`）：重启后还在。
    val recent by app.searchHistory.keywords.collectAsStateWithLifecycle()

    /** 发一次搜索：search → enrich 补全，再按 id 合并回原列表（原版 `_run_search`）。 */
    suspend fun runSearch(k: String) {
        lastKeyword = k
        // 记住了再搜：原版是搜之前就 `_remember(keyword)`。
        app.searchHistory.remember(k)
        loading = true
        error = null
        // 条数与原版一致取 100（`SearchScreen.gd:168` 传的是 SEARCH_LIMIT，不是默认的 30）。
        val outcome = app.api.search(k, SEARCH_LIMIT)
        // 迟到的回包：用户已经改了关键词，直接丢掉，别覆盖当前结果（原版 `_on_search_completed`）。
        if (keyword.trim() != lastKeyword) return
        outcome.fold(
            onSuccess = { songs ->
                // 搜索接口不回专辑/时长，用 enrich 补上再按 id 合并（原版靠 songs_enriched 重铺）。
                val enriched = runCatching { app.api.enrich(songs) }.getOrDefault(emptyList())
                val byId = enriched.associateBy { it.id }
                results = songs.map { s -> byId[s.id] ?: s }
                searched = true
            },
            onFailure = { e ->
                results = emptyList()
                searched = true
                error = "搜索失败：" + TextGuard.mask(e.message ?: "未知错误")
            },
        )
        loading = false
    }

    // 输入防抖：关键词一变就重来，上一个协程被取消（等于作废迟到回包）。
    // 清空输入＝回到历史列表（原版 `_on_query_changed` → `_show_history()`）。
    LaunchedEffect(keyword) {
        val k = keyword.trim()
        if (k.isEmpty()) {
            results = emptyList()
            searched = false
            error = null
            loading = false
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        // 停手了但关键词没变（例如刚按回车搜过）就跳过，别白跑一趟（原版 `_on_debounce_timeout`）。
        if (k == lastKeyword) return@LaunchedEffect
        runSearch(k)
    }

    val visible = app.blacklist.filterSongs(results)
    val currentId = current?.id
    val albums = if (albumMode) groupAlbums(visible) else emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = Dims.PAD_SCREEN.dp, end = Dims.PAD_SCREEN.dp, top = CONTENT_TOP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(CONTENT_GAP_DP.dp),
    ) {
        Text(text = "SEARCH", color = c.textDim, fontSize = Typo.EYEBROW.sp)
        Text(
            text = "找点什么？",
            color = c.text,
            fontSize = Typo.DISPLAY.sp,
            fontWeight = FontWeight.SemiBold,
        )

        SearchField(
            value = keyword,
            onValueChange = { keyword = it },
            onSubmit = {
                val k = keyword.trim()
                // 回车＝立刻搜，不等防抖（原版 `_on_query_submitted`）。
                if (k.isNotEmpty()) scope.launch { runSearch(k) }
            },
        )

        // ---- 单曲 / 专辑：切模式只重铺结果，不重新请求 ----------------------
        Row(horizontalArrangement = Arrangement.spacedBy(TAB_GAP_DP.dp)) {
            ModeTab(text = "单曲", active = !albumMode, onClick = { albumMode = false })
            ModeTab(text = "专辑", active = albumMode, onClick = { albumMode = true })
        }

        // ---- 结果区：只有它滚动 ---------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LIST_GAP_DP.dp),
        ) {
            when {
                // 原版搜索时会先清空结果区，只在页尾留一行 `搜索中…`。
                loading -> Unit

                error != null -> MessageCard(error!!)

                keyword.trim().isEmpty() -> RecentSearches(
                    recent = recent,
                    onPick = { keyword = it },
                    onRemove = { app.searchHistory.remove(it) },
                    onClearAll = { app.searchHistory.clear() },
                )

                visible.isEmpty() && searched -> EmptyState(
                    icon = "search",
                    // 逐字照抄原版（`SearchScreen.gd:278-280`）。
                    title = "没找到「$lastKeyword」",
                    hint = "换个关键词试试\n歌手/ 歌名 / 专辑名都可以",
                )

                albumMode -> {
                    if (albums.isEmpty()) {
                        MessageCard("这次搜索结果里没有专辑信息\n（切回「单曲」看看）")
                    } else {
                        SectionHeader("专辑", "${albums.size} 首")
                        albums.forEach { album ->
                            AlbumRow(album = album, onClick = { shell.openAlbum(album) })
                        }
                    }
                }

                else -> {
                    SectionHeader("搜索结果", "${visible.size} 首")
                    visible.forEach { song ->
                        SongRow(
                            track = song,
                            playing = currentId != null && song.id == currentId,
                            onClick = {
                                app.playback.playSong(song, visible)
                                shell.openPlayer()
                            },
                            onLongClick = { shell.showSongActions(song) },
                            // 用户 m05292 项⑥：每行右侧的「+」→ 播放列表选择器 → 加进去并按当前播放模式起播。
                            onAdd = { shell.addToQueue(song) },
                        )
                    }
                }
            }
        }

        // ---- 页尾加载行：居中、FONT_BODY / TEXT_DIM（原版 `_loading`）-------
        if (loading) {
            Text(
                text = "搜索中…",
                color = c.textDim,
                fontSize = Typo.BODY.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 把结果按专辑名聚合（原版 `_group_albums`）：没有专辑名的歌跳过，其余按**首次出现的顺序**
 * 分组，封面与歌手取该组第一首。album 字段来自 `MusicApi.enrich` 的元数据补全。
 */
private fun groupAlbums(songs: List<Track>): List<Album> {
    val order = mutableListOf<String>()
    val groups = LinkedHashMap<String, MutableList<Track>>()
    songs.forEach { song ->
        val name = song.album.trim()
        if (name.isEmpty()) return@forEach
        if (!groups.containsKey(name)) {
            groups[name] = mutableListOf()
            order += name
        }
        groups.getValue(name) += song
    }
    return order.map { name ->
        val list = groups.getValue(name)
        Album(
            name = name,
            cover = list.first().cover,
            trackCount = list.size,
            artist = list.first().artist,
            songs = list,
        )
    }
}

/**
 * 搜索输入框。**圆角矩形**（`RADIUS_SMALL`），不是胶囊 —— 这是用户明确要求的一条。
 * 提示文案逐字照抄原版 `placeholder_text`（`SearchScreen.gd:80`）。
 * 聚焦态换 `SURFACE_HI` 底 + `ACCENT` 描边（`AppTheme.gd:660-665`）；
 * 右侧 `✕` 对应原版 `clear_button_enabled = true`（`:81`），有字才出现，点了清空。
 */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit) {
    val c = PullTheme.colors
    val shape = pullShape(Dims.RADIUS_SMALL)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = TextStyle(color = c.text, fontSize = Typo.BODY.sp),
        cursorBrush = SolidColor(c.accent),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = FIELD_MIN_HEIGHT_DP.dp)
                    .clip(shape)
                    .background(if (focused) c.surfaceHi else c.surface)
                    .border(1.dp, if (focused) c.accent else c.line, shape)
                    .padding(horizontal = FIELD_PAD_H_DP.dp, vertical = FIELD_PAD_V_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text("搜索歌曲、歌手、专辑", color = c.textDim, fontSize = Typo.BODY.sp)
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    Text(
                        text = "✕",
                        color = c.textDim,
                        fontSize = Typo.META.sp,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .clip(pullShape(Dims.RADIUS_SMALL))
                            .clickable { onValueChange("") }
                            .padding(4.dp),
                    )
                }
            }
        },
    )
}

/**
 * 搜索模式标签。原版 `_make_mode_tab` + `_style_mode_tabs`（`SearchScreen.gd:189-215`）：
 * 最小 180×84px、字 `FONT_SUB`；选中是 `button_box(ACCENT)` 实底 + 字色 `BG`，
 * 未选中是 `button_box(SURFACE)` 实底 + 字色 `TEXT_DIM`，两者都**无描边**。
 */
@Composable
private fun ModeTab(text: String, active: Boolean, onClick: () -> Unit) {
    val c = PullTheme.colors
    Box(
        modifier = Modifier
            .widthIn(min = TAB_MIN_WIDTH_DP.dp)
            .heightIn(min = TAB_MIN_HEIGHT_DP.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(if (active) c.accent else c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = BUTTON_PAD_H_DP.dp, vertical = BUTTON_PAD_V_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = if (active) c.bg else c.textDim, fontSize = Typo.SUB.sp)
    }
}

/**
 * 专辑模式下的一张专辑卡。原版铺的是 `Card.make_playlist_card`（= `UIFactory._make_row`，
 * `UIFactory.gd:378-390`），它是**整宽一行**（高 192px、封面 152px、副标题「N 首 · 歌手」），
 * 直接堆在竖排的 `_results_box` 里 —— 所以这里不是横滑的 `PlaylistTile` 格子。
 */
@Composable
private fun AlbumRow(album: Album, onClick: () -> Unit) {
    val c = PullTheme.colors
    val meta = buildString {
        append("${album.trackCount} 首")
        if (album.artist.isNotBlank()) append(" · ").append(album.artist)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ALBUM_ROW_MIN_HEIGHT_DP.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .clickable(onClick = onClick)
            .padding(ROW_PAD_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_INNER_GAP_DP.dp),
    ) {
        // 封面圆角照原版 `UIFactory.gd:171 ART_RADIUS := 10`（10÷2.5 = 4dp）——
        // 不传就落到 `Dims.RADIUS_SMALL` 的 13dp，方块几乎被削成圆片。
        Cover(url = album.cover, size = ALBUM_ART_DP, corner = 4)
        Column(
            modifier = Modifier.weight(1f),
            // 两行文字行距 8px（`UIFactory.gd:923` separation 8）⇒ 3.2dp。
            verticalArrangement = Arrangement.spacedBy(3.2.dp),
        ) {
            Text(
                text = album.name,
                color = c.text,
                fontSize = Typo.BODY.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                color = c.textDim,
                fontSize = Typo.SUB.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 最近搜索（原版 `_show_history`，`SearchScreen.gd:344-414`）：头部「最近搜索」+ 右侧
 * 「清除全部」→ 一行提示 → **`HFlowContainer` 流式排布的词 chips**（每条 = 词 + 单独的 `✕`）。
 * 点词直接搜，点 `✕` 只删这一条。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentSearches(
    recent: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    val c = PullTheme.colors
    if (recent.isEmpty()) {
        // 逐字照抄原版（`SearchScreen.gd:349-351`），含图标盘。
        EmptyState(
            icon = "search",
            title = "输入关键词开始搜索",
            hint = "歌手/ 歌名 / 专辑名都可以\n搜过的词会留在历史里",
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "最近搜索",
            color = c.text,
            fontSize = Typo.BODY.sp,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .heightIn(min = CLEAR_ALL_HEIGHT_DP.dp)
                .clip(pullShape(Dims.RADIUS_SMALL))
                .background(c.surface)
                .clickable(onClick = onClearAll)
                .padding(horizontal = BUTTON_PAD_H_DP.dp, vertical = BUTTON_PAD_V_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "清除全部", color = c.textDim, fontSize = Typo.META.sp)
        }
    }
    Text(
        text = "点词直接搜 · 点 ✕ 只删这一条 · 「清除全部」清空历史",
        color = c.textDim,
        fontSize = Typo.META.sp,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(CHIP_GAP_DP.dp),
    ) {
        recent.forEach { word ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // 词与 `✕` 之间的 10px（`SearchScreen.gd:388`）⇒ 4dp。
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(min = CHIP_MIN_HEIGHT_DP.dp)
                        .clip(pullShape(Dims.RADIUS_SMALL))
                        .background(c.surface)
                        .clickable { onPick(word) }
                        .padding(horizontal = BUTTON_PAD_H_DP.dp, vertical = BUTTON_PAD_V_DP.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = word, color = c.text, fontSize = Typo.SUB.sp)
                }
                Box(
                    modifier = Modifier
                        .widthIn(min = CHIP_REMOVE_WIDTH_DP.dp)
                        .heightIn(min = CHIP_MIN_HEIGHT_DP.dp)
                        .clip(pullShape(Dims.RADIUS_SMALL))
                        // `panel_box(SURFACE_HI, RADIUS_SMALL, 26)`（`SearchScreen.gd:406`）。
                        .background(c.surfaceHi)
                        .clickable { onRemove(word) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✕", color = c.textDim, fontSize = Typo.META.sp)
                }
            }
        }
    }
}
