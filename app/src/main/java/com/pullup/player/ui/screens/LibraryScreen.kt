package com.pullup.player.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.data.AppContainer
import com.pullup.player.data.ImportResult
import com.pullup.player.data.Track
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.LocalShell
import com.pullup.player.ui.Shell
import com.pullup.player.ui.components.EmptyState
import com.pullup.player.ui.components.SectionHeader
import com.pullup.player.ui.components.SongRow
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.Typo
import com.pullup.player.ui.theme.pullShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- 页面几何（原稿 px ÷ 2.5 → dp / sp，与 `Dims` / `Typo` 同一换算口径）------
// 这些值在原版里是本页自己的常量，`Dims` / `Typo` 中没有对应令牌，
// 按 `NavBar.kt` / `PasswordOverlay.kt` 的既有先例在本文件内定义并注明出处。

/** 切换 Tab 的最小高度：`TAB_HEIGHT = 92.0`（`LibraryScreen.gd:15`）⇒ 37dp。 */
private const val TAB_HEIGHT_DP = 37

/** `Tabs` 容器间距 16px（`LibraryScreen.gd:70`）⇒ 6dp。 */
private const val TAB_GAP_DP = 6

/** Tab 内边距：左右 40px / 上下 20px（`LibraryScreen.gd:155-158`）⇒ 16 / 8dp。 */
private const val TAB_PAD_H_DP = 16
private const val TAB_PAD_V_DP = 8

/** `Content` 上内边距 72px（`LibraryScreen.gd:54`）⇒ 29dp；左右走 `Dims.PAD_SCREEN`。
 *  下边距原版是 −366px（给迷你条 200 + 底栏 160 让位），Kotlin 侧由外壳的
 *  PlayerBar / NavBar 承担（`PullUpRoot.kt:189-200`），页面里不再补。 */
private const val CONTENT_TOP_DP = 29

/** `Content` 间距 22px（`LibraryScreen.gd:56`）⇒ 9dp。 */
private const val CONTENT_GAP_DP = 9

/** `List` 与两个列表容器的间距 6px（`LibraryScreen.gd:88,94,100`）⇒ 2dp。 */
private const val LIST_GAP_DP = 2

/** 空态几何（间距 18px / 按钮高 96px / 图标盘 180px）已收进 `ui.components.EmptyState`。 */

/** 段头小按钮的最小高度：`清空`（最近播放）70px ⇒ 28dp（`LibraryScreen.gd:206`）；
 *  `导入` / `清空`（本地音乐）84px ⇒ 34dp（`LibraryScreen.gd:273,281`）。 */
private const val SMALL_BUTTON_HEIGHT_DP = 28
private const val HEADER_BUTTON_HEIGHT_DP = 34

/** 主题默认按钮的内边距 30 / 18px（`AppTheme.gd:627-630`）⇒ 12 / 7dp。 */
private const val HEADER_BUTTON_PAD_H_DP = 12
private const val HEADER_BUTTON_PAD_V_DP = 7

/** 两个 Tab 的下标，对应原版 `enum Tab { RECENT, LOCAL }`（`LibraryScreen.gd:13`）。 */
private const val TAB_RECENT = 0
private const val TAB_LOCAL = 1

/**
 * 音乐库（Tab 页，`page = 2`）。移植自 Godot 版 `scripts/ui/LibraryScreen.gd`。
 *
 * 版式与原版一致：eyebrow `LIBRARY` + 大标题 `音乐库` + **两枚等宽切换 Tab**
 * （`最近播放` / `本地音乐`，最小高 92px = 37dp），Tab 下方才是列表滚动区。
 * 「我的收藏」**不在本页**：原版把那一块改成了这里的「最近播放」（数据来自 `History`），
 * 收藏本身只留在首页的「我的歌单」架里（`LibraryScreen.gd:6-7`）。
 *
 * 与原版的差异（有意为之）：
 * - 原版「导入」走 `FileDialog`（桌面文件对话框）。Android 上没有该 API，
 *   改用 **SAF**（`ACTION_OPEN_DOCUMENT`）多选音频，由 `LocalLibrary.importFromUris`
 *   流式拷贝进私有目录 —— 不需要任何存储权限，且与源文件无关。
 * - 原版空态是 `Card.make_empty_state`（180px 图标盘 + 标题 + 说明 + 主按钮），
 *   现已用 `ui.components` 的 `EmptyState` 复刻（几何见其 KDoc），文案逐字照抄原版。
 * - 顶部无返回钮：它和外层壳的 PlayerBar / NavBar 一起构成 Tab 区，返回由外壳管。
 *
 * 列表一律先过 `blacklist.filterSongs`：屏幕上这份列表同时就是播放队列，
 * 过滤在这里做掉，队列自然干净（原版注释里反复强调的那条）。
 */
@Composable
fun LibraryScreen() {
    val app = LocalApp.current
    val shell = LocalShell.current
    val c = PullTheme.colors

    val history by app.history.entries.collectAsStateWithLifecycle()
    val local by app.localLibrary.tracks.collectAsStateWithLifecycle()
    // 在播那首的 id：用来给列表里对应的行加强调色。
    val current by app.playback.current.collectAsStateWithLifecycle()

    // 当前 Tab（原版 `current_tab`，默认落在「最近播放」，`LibraryScreen.gd:17`）。
    var tab by remember { mutableIntStateOf(TAB_RECENT) }

    val scope = rememberCoroutineScope()
    var importMessage by remember { mutableStateOf<String?>(null) }

    // SAF 多选。返回的 content:// URI 交给曲库流式拷贝（内部在 IO 线程上做）。
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { app.localLibrary.importFromUris(uris) }
            importMessage = buildImportMessage(result)
            // 导入完强制切到「本地音乐」，让用户立刻看到刚进来的文件（`LibraryScreen.gd:326-327`）。
            tab = TAB_LOCAL
        }
    }

    // 导入结果的提示 3 秒后收起（原版 `get_tree().create_timer(3.0)`，`LibraryScreen.gd:325`）。
    LaunchedEffect(importMessage) {
        if (importMessage != null) {
            delay(3000)
            importMessage = null
        }
    }

    val recentSongs = app.blacklist.filterSongs(history.map { it.song })
    val localSongs = app.blacklist.filterSongs(local)
    val currentId = current?.id

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = Dims.PAD_SCREEN.dp,
                end = Dims.PAD_SCREEN.dp,
                top = CONTENT_TOP_DP.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(CONTENT_GAP_DP.dp),
    ) {
        Text(text = "LIBRARY", color = c.textDim, fontSize = Typo.EYEBROW.sp)
        Text(
            text = "音乐库",
            color = c.text,
            fontSize = Typo.DISPLAY.sp,
            fontWeight = FontWeight.SemiBold,
        )

        // ---- Tabs：两枚等宽按钮，互斥切换 ----------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TAB_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryTab("最近播放", active = tab == TAB_RECENT, modifier = Modifier.weight(1f)) {
                tab = TAB_RECENT
            }
            LibraryTab("本地音乐", active = tab == TAB_LOCAL, modifier = Modifier.weight(1f)) {
                tab = TAB_LOCAL
            }
        }

        // ---- 列表滚动区（Tab 之外的页头不跟着滚）---------------------------
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LIST_GAP_DP.dp),
        ) {
            if (tab == TAB_RECENT) {
                RecentSection(recentSongs, currentId, app, shell)
            } else {
                LocalSection(
                    // 计数用整库原始条数（原版是 `LocalLibrary.count()`，`LibraryScreen.gd:265`），
                    // 与下面这份过了黑名单的列表不是同一个数：黑名单只挡显示，不改计数。
                    rawCount = app.localLibrary.count(),
                    songs = localSongs,
                    currentId = currentId,
                    app = app,
                    shell = shell,
                    onImport = { picker.launch(AUDIO_MIME_TYPES) },
                )
            }
        }

        // ---- 导入结果提示：原版是挂在 `Content` 上、位于 `Scroll` 之后的 `Loading` Label
        //      （`LibraryScreen.gd:111-118`）：居中、`FONT_BODY`/`TEXT_DIM`，所以它在
        //      滚动区**外面**、页面底部，而不是列表里的一张卡。------------------------
        importMessage?.let { text ->
            Text(
                text = text,
                color = c.textDim,
                fontSize = Typo.BODY.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 切换 Tab。原版 `_make_tab()` + `_style_tab()`（`LibraryScreen.gd:121-133, 153-169`）：
 * 等宽（`SIZE_EXPAND_FILL`）、最小高 `TAB_HEIGHT = 92px`、选中是 `ACCENT` 实底 +
 * `ON_ACCENT` 字色，未选中是 `SURFACE` 实底 + `TEXT_DIM` 字色；两者都**只有填充、
 * 没有描边**，字号走主题默认的 `FONT_BODY`（`AppTheme.gd:578-581, 607`）。
 */
@Composable
private fun LibraryTab(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = PullTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = TAB_HEIGHT_DP.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(if (active) c.accent else c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = TAB_PAD_H_DP.dp, vertical = TAB_PAD_V_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (active) c.onAccent else c.textDim,
            fontSize = Typo.BODY.sp,
        )
    }
}

/**
 * 段头右侧的小按钮（`导入` / `清空`）。原版这两颗走的是**主题默认按钮框**
 * （`AppTheme.gd:625-631`）：`SURFACE` 实底、无描边、内边距 30/18px，
 * 调用方只覆盖字号与字色（`LibraryScreen.gd:203-210, 270-285`）。
 */
@Composable
private fun HeaderButton(
    text: String,
    fontSize: TextUnit,
    color: Color,
    minHeightDp: Int,
    onClick: () -> Unit,
) {
    val c = PullTheme.colors
    Box(
        modifier = Modifier
            .heightIn(min = minHeightDp.dp)
            .clip(pullShape(Dims.RADIUS_SMALL))
            .background(c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = HEADER_BUTTON_PAD_H_DP.dp, vertical = HEADER_BUTTON_PAD_V_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = color, fontSize = fontSize)
    }
}

/**
 * 「最近播放」：48 小时内的听歌记录。空的时候**只有空态、没有段头**
 * （原版 `_render_recent()` 在空分支里直接 `return`，`LibraryScreen.gd:181-185`）。
 */
@Composable
private fun RecentSection(
    songs: List<Track>,
    currentId: Long?,
    app: AppContainer,
    shell: Shell,
) {
    val c = PullTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LIST_GAP_DP.dp),
    ) {
        if (songs.isEmpty()) {
            // 逐字照抄原版空态（`LibraryScreen.gd:182-184`）。
            EmptyState(
                icon = "play",
                title = "还没有听歌记录",
                hint = "播放过的歌会出现在这里\n最多保留 48 小时",
            )
        } else {
            SectionHeader(
                title = "最近播放",
                // 计数是**过滤后**的条数（`LibraryScreen.gd:198`）。
                hint = "${songs.size} 首",
                trailing = {
                    HeaderButton(
                        text = "清空",
                        fontSize = Typo.META.sp,
                        color = c.textDim,
                        minHeightDp = SMALL_BUTTON_HEIGHT_DP,
                        onClick = { app.history.clear() },
                    )
                },
            )
            songs.forEach { song ->
                SongRow(
                    track = song,
                    playing = currentId != null && song.id == currentId,
                    onClick = {
                        // 先起播再开播放页：原版也是这个顺序（`LibraryScreen.gd:235-240`）。
                        app.playback.playSong(song, songs)
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

/**
 * 「本地音乐」：导入的本地文件。段头**恒常显示**（原版在空判定之前就 add_child，
 * `LibraryScreen.gd:286`），所以空的时候也能看到 `导入` 与 `清空`。
 */
@Composable
private fun LocalSection(
    rawCount: Int,
    songs: List<Track>,
    currentId: Long?,
    app: AppContainer,
    shell: Shell,
    onImport: () -> Unit,
) {
    val c = PullTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LIST_GAP_DP.dp),
    ) {
        SectionHeader(
            title = "本地音乐",
            hint = "$rawCount 首",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(TAB_GAP_DP.dp)) {
                    HeaderButton(
                        text = "导入",
                        fontSize = Typo.SUB.sp,
                        // 原版这颗没有覆盖字色，走主题默认的 TEXT（`LibraryScreen.gd:270-275`）。
                        color = c.text,
                        minHeightDp = HEADER_BUTTON_HEIGHT_DP,
                        onClick = onImport,
                    )
                    HeaderButton(
                        text = "清空",
                        fontSize = Typo.META.sp,
                        color = c.textDim,
                        minHeightDp = HEADER_BUTTON_HEIGHT_DP,
                        onClick = { app.localLibrary.clear() },
                    )
                }
            },
        )

        if (songs.isEmpty()) {
            // 空态：图标盘 + 标题 + 说明 + 主按钮（`LibraryScreen.gd:291-294`），文案逐字照抄。
            EmptyState(
                icon = "add",
                title = "还没有本地音乐",
                hint = "支持 mp3 / ogg / wav\n导入后会复制到应用私有目录，与源文件无关",
                actionText = "导入音乐",
                onAction = onImport,
            )
        } else {
            songs.forEach { song ->
                SongRow(
                    track = song,
                    playing = currentId != null && song.id == currentId,
                    onClick = {
                        // 队列就是过滤后的本地列表（原版 `_local_visible`，`LibraryScreen.gd:305-309`）。
                        app.playback.playSong(song, songs)
                        shell.openPlayer()
                    },
                    onLongClick = { shell.showSongActions(song) },
                    // 本地曲目 id 为负，`Playlists.addSong` 会按原版口径拒收并给出中文原因，
                    // 所以这里照常给「+」——按钮在，反馈也在，不是死按钮。
                    onAdd = { shell.addToQueue(song) },
                )
            }
        }
    }
}

/**
 * SAF 的 MIME 过滤。`OpenMultipleDocuments` 只认 MIME 不认扩展名，
 * 这里把三种格式对应的 MIME 全列上；部分机型对通配的 audio 类型支持不好，故显式列出。
 * （注意不要在块注释里写通配路径：Kotlin 的块注释可嵌套，一个斜杠星号会吞掉后面的代码。）
 */
private val AUDIO_MIME_TYPES = arrayOf(
    "audio/mpeg", // mp3
    "audio/ogg", // ogg
    "audio/x-wav", // wav
    "audio/wav",
    "audio/flac",
)

/**
 * 导入结果文案：与 Godot 版逐字一致 ——
 * `已导入 %d 个文件` + 可选 `，跳过 %d 个（格式不支持）` + 可选 `%d 个复制失败`。
 * 注意失败那一段**前面没有逗号**，原版就是这么拼的（`LibraryScreen.gd:321`）。
 */
private fun buildImportMessage(result: ImportResult): String {
    val parts = StringBuilder("已导入 ${result.imported} 个文件")
    if (result.skipped.isNotEmpty()) parts.append("，跳过 ${result.skipped.size} 个（格式不支持）")
    if (result.failed.isNotEmpty()) parts.append("${result.failed.size} 个复制失败")
    return parts.toString()
}
