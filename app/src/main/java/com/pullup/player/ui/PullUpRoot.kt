package com.pullup.player.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pullup.player.data.AppContainer
import com.pullup.player.data.Track
import com.pullup.player.ui.components.AmbientBackground
import com.pullup.player.ui.components.BootSplash
import com.pullup.player.ui.components.BootSplashState
import com.pullup.player.ui.components.PullNavBar
import com.pullup.player.ui.screens.AlbumScreen
import com.pullup.player.ui.screens.AddToPlaylistSheet
import com.pullup.player.ui.screens.AddToQueueListSheet
import com.pullup.player.ui.screens.AppearanceScreen
import com.pullup.player.ui.screens.ArtistScreen
import com.pullup.player.ui.screens.EatenScreen
import com.pullup.player.ui.screens.HomeScreen
import com.pullup.player.ui.screens.LibraryScreen
import com.pullup.player.ui.screens.LyricsScreen
import com.pullup.player.ui.screens.PasswordOverlay
import com.pullup.player.ui.screens.PlayerPage
import com.pullup.player.ui.screens.PlaylistEditorOverlay
import com.pullup.player.ui.screens.PlaylistOverlay
import com.pullup.player.ui.screens.ProfileScreen
import com.pullup.player.ui.screens.SearchScreen
import com.pullup.player.ui.screens.SongActionSheet
import com.pullup.player.ui.screens.TasteScreen
import com.pullup.player.ui.theme.Dims
import com.pullup.player.ui.theme.Motion
import com.pullup.player.ui.theme.PullTheme
import com.pullup.player.ui.theme.pullShape
import kotlinx.coroutines.delay

/**
 * 界面根与导航外壳，等价于 Godot 版 `scripts/ui/MainUI.gd`。
 *
 * 结构自上而下：
 * - `AmbientBackground` 常驻底层（播放页透明、要靠它透出环境光）；
 * - 4 个 Tab 页互斥显示（搜索不是 Tab，是从首页推入的整页）；
 * - 常驻迷你播放条 + 底部导航（播放页打开时两者隐藏）；
 * - 一摞整页浮层，后压前，返回键按叠放倒序逐层收。
 */
@Composable
fun PullUpRoot(app: AppContainer) {
    // 用 MutableState 对象而不是 `var page by`：外层是函数不是类，匿名 Shell 里写不了
    // `this@PullUpRoot.page`（函数名不是合法的 this 标签），而捕获这个 state 就没问题。
    val pageState = remember { mutableStateOf(Shell.PAGE_HOME) }
    val page = pageState.value
    val stack = remember { mutableStateListOf<Overlay>() }
    var exitArmed by remember { mutableStateOf(false) }
    var exitHint by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 启动初始化动画（原版 `scripts/ui/BootSplash.gd` + `main.gd:54` 的 `_should_show_boot()`）。
    // `shouldShow` 有副作用（置「本进程已播过」），所以必须包在 `remember` 里只求值一次，
    // 否则每次重组都会重新判定、把开关烧掉。
    val showBoot = remember { BootSplashState.shouldShow(app.preferences.bootAnimation()) }
    var bootDone by remember { mutableStateOf(!showBoot) }

    // 冷启动：把上次的队列与曲目恢复出来（**不自动播放**）。
    LaunchedEffect(Unit) { app.playback.restoreSession() }

    val shellImpl = remember(app) {
        object : Shell {
            override fun switchPage(target: Int, animate: Boolean) {
                stack.clear()
                pageState.value = target
            }

            override fun goSearch() {
                stack.clear()
                pageState.value = Shell.PAGE_SEARCH
            }

            override fun closeSearch() {
                pageState.value = Shell.PAGE_HOME
            }

            override fun openPlayer() = push(Overlay.Player)
            override fun closePlayer() = popTo { it is Overlay.Player }

            override fun openArtist(song: Track) = push(Overlay.Artist(song))
            override fun closeArtist() = popTo { it is Overlay.Artist }

            override fun openAlbum(album: com.pullup.player.data.Album) = push(Overlay.Album(album))
            override fun closeAlbum() = popTo { it is Overlay.Album }

            override fun openAppearance() = push(Overlay.Appearance)
            override fun openTaste() = push(Overlay.Taste)
            override fun openLyrics(coverRect: androidx.compose.ui.geometry.Rect?) =
                push(Overlay.Lyrics(coverRect))

            override fun openPlaylist(id: String) {
                // 私密且本次会话未解锁 → 先要密码（原版 `_on_playlist_activated`）。
                popTo { it is Overlay.Password }
                if (app.playlists.needsPassword(id) && !app.playlists.isUnlocked(id)) {
                    push(Overlay.Password(id))
                } else {
                    push(Overlay.Playlist(id))
                }
            }

            override fun openEditor() = push(Overlay.Editor)
            override fun openEditorFor(song: Track) = push(Overlay.EditorFor(song))
            override fun showSongActions(song: Track) = push(Overlay.Actions(song))
            override fun addToPlaylist(song: Track) = push(Overlay.AddTo(song))
            override fun addToQueueList(song: Track) = push(Overlay.AddToQueue(song))
            override fun openEaten() = push(Overlay.Eaten)

            override fun openUrl(url: String) {
                openInBrowser(context, url)
            }

            override fun handleBack(): Boolean {
                if (stack.isNotEmpty()) {
                    stack.removeAt(stack.lastIndex)
                    return true
                }
                // 必须现读 pageState.value，不能读外层那个 `val page`：
                // 这个匿名对象是 remember(app) 出来的、只建一次，捕获的 `page` 会永远停在
                // 首次组合时的值（PAGE_HOME），于是「非首页 → 回首页」这一层永远不触发。
                if (pageState.value != Shell.PAGE_HOME) {
                    pageState.value = Shell.PAGE_HOME
                    return true
                }
                // 已在首页：两秒内再按一次才退出（DOUBLE_BACK_MS）。
                if (exitArmed) {
                    (context as? Activity)?.finish()
                    return true
                }
                exitArmed = true
                exitHint = true
                return true
            }

            private fun push(o: Overlay) {
                // 同类浮层只留最上面一张，避免连点堆出一摞。
                stack.removeAll { it::class == o::class }
                stack.add(o)
            }

            private fun popTo(predicate: (Overlay) -> Boolean) {
                val i = stack.indexOfLast(predicate)
                if (i >= 0) stack.removeAt(i)
            }
        }
    }

    LaunchedEffect(exitArmed, exitHint) {
        if (exitArmed) {
            delay(2000)
            exitArmed = false
        }
        if (exitHint) {
            delay(1200)
            exitHint = false
        }
    }

    BackHandler { shellImpl.handleBack() }

    CompositionLocalProvider(LocalShell provides shellImpl) {
        Box(Modifier.fillMaxSize()) {
            AmbientBackground(app)

            // ---- Tab 页 / 搜索页：互斥显示 ----
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    if (page == Shell.PAGE_SEARCH) {
                        ScreenSlide(visible = true, fromRight = true) { SearchScreen() }
                    } else if (page == Shell.PAGE_LIBRARY) {
                        ScreenSlide(visible = true, fromRight = true) { LibraryScreen() }
                    } else if (page == Shell.PAGE_SETTINGS) {
                        ScreenSlide(visible = true, fromRight = true) { ProfileScreen() }
                    } else {
                        ScreenSlide(visible = true, fromRight = true) { HomeScreen() }
                    }
                }

                val playerOpen = stack.any { it is Overlay.Player }
                if (!playerOpen) {
                    val track by app.playback.current.collectAsStateWithLifecycle()
                    val playing by app.playback.playing.collectAsStateWithLifecycle()
                    val position by app.playback.position.collectAsStateWithLifecycle()
                    val duration by app.playback.duration.collectAsStateWithLifecycle()
                    val loading by app.playback.loading.collectAsStateWithLifecycle()
                    val error by app.playback.error.collectAsStateWithLifecycle()

                    val subtitle = when {
                        error != null -> error.orEmpty()
                        loading -> "加载中…"
                        track != null -> track!!.artist
                        else -> "选一首歌开始播放"
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = Dims.PAD_SCREEN.dp, vertical = 6.dp)) {
                        com.pullup.player.ui.components.PlayerBar(
                            title = track?.name ?: "未在播放",
                            subtitle = subtitle,
                            cover = track?.cover.orEmpty(),
                            playing = playing,
                            progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                            onClick = { shellImpl.openPlayer() },
                            onPrev = { app.playback.playPrevious() },
                            onToggle = { app.playback.togglePlayPause() },
                            onNext = { app.playback.playNext() },
                        )
                    }
                    PullNavBar(current = page) { shellImpl.switchPage(it) }
                    // enableEdgeToEdge 之下，导航栏区域会盖住底栏；补一段同色底垫，
                    // 否则手势条直接压在「设置」文字上。
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsBottomHeight(WindowInsets.navigationBars)
                            .background(PullTheme.colors.surface),
                    )
                }
            }

            // ---- 整页浮层（后压前）----
            stack.forEach { overlay ->
                when (overlay) {
                    is Overlay.Lyrics -> FullOverlay(onClose = { stack.remove(overlay) }) {
                        LyricsScreen(
                            coverRect = overlay.coverRect,
                            onClose = { stack.remove(overlay) },
                        )
                    }
                    is Overlay.Taste -> FullOverlay(onClose = { stack.remove(overlay) }) { TasteScreen() }
                    is Overlay.Appearance -> FullOverlay(onClose = { stack.remove(overlay) }) { AppearanceScreen() }
                    is Overlay.Album -> FullOverlay(onClose = { stack.remove(overlay) }) {
                        AlbumScreen(album = overlay.album)
                    }
                    is Overlay.Actions -> {
                        // 操作面板是底部弹层：点遮罩关掉，不参与整页淡入。
                        SongActionSheet(
                            song = overlay.song,
                            onDismiss = { stack.remove(overlay) },
                            onOpenPlaylist = { id ->
                                stack.remove(overlay)
                                shellImpl.openPlaylist(id)
                            },
                        )
                    }
                    is Overlay.AddTo -> {
                        // 「+」的选择器也是底部弹层，但加完之后要起播，所以不参与整页淡入。
                        AddToPlaylistSheet(
                            song = overlay.song,
                            onDismiss = { stack.remove(overlay) },
                        )
                    }
                    is Overlay.AddToQueue -> {
                        // 用户 m05292 项⑤⑥：「+」→ 播放列表选择器 → 按当前模式起播。
                        AddToQueueListSheet(
                            song = overlay.song,
                            onDismiss = { stack.remove(overlay) },
                        )
                    }
                    is Overlay.Artist -> FullOverlay(onClose = { stack.remove(overlay) }) {
                        ArtistScreen(song = overlay.song)
                    }
                    is Overlay.Player -> {
                        // 播放页打开时透明盖住壳层，靠 AmbientBackground 透出环境光。
                        FullOverlay(onClose = { shellImpl.closePlayer() }) {
                            PlayerPage(onClose = { shellImpl.closePlayer() })
                        }
                    }
                    is Overlay.Playlist -> FullOverlay(onClose = { stack.remove(overlay) }) {
                        PlaylistOverlay(playlistId = overlay.id)
                    }
                    is Overlay.Editor -> FullOverlay(onClose = { stack.remove(overlay) }) {
                        PlaylistEditorOverlay()
                    }
                    // 「新建歌单」从「添加到歌单」面板进来：建好就把那首放进去
                    // （原版 `ActionSheet.gd:248-258` 的 `_pending_add_after_create`）。
                    is Overlay.EditorFor -> FullOverlay(onClose = { stack.remove(overlay) }) {
                        PlaylistEditorOverlay(
                            onCreated = { id ->
                                app.playlists.addSong(id, overlay.song)
                                app.playlists.find(id)?.let { pl ->
                                    app.playback.playSong(overlay.song, pl.songs)
                                }
                                shellImpl.openPlayer()
                            },
                        )
                    }
                    is Overlay.Password -> PasswordOverlay(
                        playlistId = overlay.id,
                        onUnlocked = {
                            stack.remove(overlay)
                            stack.add(Overlay.Playlist(overlay.id))
                        },
                        onEaten = {
                            stack.remove(overlay)
                            stack.add(Overlay.Eaten)
                        },
                        onClose = { stack.remove(overlay) },
                    )
                    is Overlay.Eaten -> EatenScreen(onClose = { stack.remove(overlay) })
                }
            }

            // ---- 退出提示胶囊 ----
            androidx.compose.animation.AnimatedVisibility(
                visible = exitHint,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 160.dp),
            ) {
                Text(
                    text = "再按一次返回键退出",
                    color = PullTheme.colors.text,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(pullShape(Dims.RADIUS_PILL))
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }

            // ---- 启动动画层（压在最上面，播完自毁）----
            if (!bootDone) {
                BootSplash(onDone = { bootDone = true })
            }
        }
    }
}

/** Tab 页入场的整幅侧滑。原版：旧页起手帧直接消失（不做淡出），新页从侧面滑入。 */
@Composable
private fun ScreenSlide(visible: Boolean, fromRight: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = tween(Motion.ANIM_VIEW_IN.toInt() * 1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            initialOffsetX = { if (fromRight) it else -it },
        ),
    ) { content() }
}

/**
 * 整页浮层：淡入 + 轻微上滑。
 *
 * [onClose] 只用来在**返回键**时收掉这一层 —— `PullUpRoot` 已经把 `BackHandler` 统一挂在
 * 根上按叠放顺序收栈，所以这里不再收一次，否则一次返回会连收两层。页面自己的关闭按钮
 * 走 `LocalShell`（`closeAlbum()` 等），不走这个回调。
 */
@Composable
private fun FullOverlay(onClose: () -> Unit, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    androidx.compose.animation.AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 22 },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 26 },
        modifier = Modifier.fillMaxSize(),
    ) { content() }
}

/** 浮层种类。整页浮层共用一种进出场，操作面板另算。 */
private sealed interface Overlay {
    /**
     * 歌词页。[coverRect] = 播放页大封面的窗口矩形，用来做「封面自己移上去」的补间
     * （原版 `LyricsScreen.gd:190-218`）。null = 直接落位不飞入。
     */
    data class Lyrics(val coverRect: androidx.compose.ui.geometry.Rect?) : Overlay
    data object Taste : Overlay
    data object Appearance : Overlay
    data object Player : Overlay
    data object Editor : Overlay
    // 「新建歌单」从「添加到歌单」进来：带着那首歌，建好后要把它塞进新歌单。
    data class EditorFor(val song: Track) : Overlay
    data object Eaten : Overlay
    data class Artist(val song: Track) : Overlay
    // 名字与 `data.Album` 同名，这里必须写全限定类型，否则嵌套类会自指成死循环类型。
    data class Album(val album: com.pullup.player.data.Album) : Overlay
    data class Actions(val song: Track) : Overlay
    data class AddTo(val song: Track) : Overlay
    data class AddToQueue(val song: Track) : Overlay
    data class Playlist(val id: String) : Overlay
    data class Password(val id: String) : Overlay
}

/** 保留：退出提示与浮层淡出都用它算出真实时长。 */
internal fun overlayInMs(motionScale: Float): Int = (Motion.ANIM_VIEW_IN / motionScale * 1000f).toInt().coerceAtLeast(1)

/**
 * 用系统浏览器打开链接。
 *
 * 原版靠 `OS.shell_open()`；Android 侧没有等价物，必须显式发 Intent。加
 * `FLAG_ACTIVITY_NEW_TASK` 是因为这里的 Context 未必是 Activity。
 * 没有浏览器时 `startActivity` 会抛 [android.content.ActivityNotFoundException]，
 * 这是概率极低但真实存在的路径 —— 吞掉比崩掉好。
 */
private fun openInBrowser(context: android.content.Context, url: String) {
    runCatching {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
