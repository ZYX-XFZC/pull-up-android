package com.pullup.player.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import com.pullup.player.data.Album
import com.pullup.player.data.Track

/**
 * 导航外壳。移植自 Godot 版 `scripts/ui/MainUI.gd` 的对外 API 集合
 * （`open_player` / `open_artist` / `open_album` / `open_appearance` / `open_taste` /
 * `show_song_actions` / `switch_page` / `handle_back`）。
 *
 * 页面通过 `LocalShell.current` 取它，而不是层层透传回调 —— 原版就是靠
 * `get_parent()` 直接调外壳，等价于这里的 CompositionLocal。
 *
 * 叠放顺序（下→上，也是返回键的退栈顺序）：
 * 歌手 → 操作面板 → 专辑 → 外观 → 口味偏好 → 歌词 → 播放页 → 歌单详情 → 播放页。
 */
interface Shell {
    /** 切 Tab。[animate] = false 用于冷启动恢复（不播入场动画）。 */
    fun switchPage(page: Int, animate: Boolean = true)

    fun goHome() = switchPage(PAGE_HOME)
    fun goLibrary() = switchPage(PAGE_LIBRARY)
    fun goSettings() = switchPage(PAGE_SETTINGS)

    /** 搜索不是 Tab，是从首页顶部推入的整页（原版 `go_search()`）。 */
    fun goSearch()
    fun closeSearch()

    fun openPlayer()
    fun closePlayer()

    fun openArtist(song: Track)
    fun closeArtist()

    fun openAlbum(album: Album)
    fun closeAlbum()

    fun openAppearance()
    fun openTaste()
    /**
     * 沉浸式歌词页。原版 `LyricsScreen.open(from_cover: Rect2)`（`LyricsScreen.gd:190`）拿
     * 播放页大封面的全局矩形做「同一张封面飞上去」的补间，退场再补回同一个矩形。
     *
     * [coverRect] 就是那个矩形（窗口坐标，单位 px）。为 null 或宽高无效时歌词页直接落位，
     * 不飞入 —— 对应原版 `from_cover.size.x > 1.0` 的兜底分支。
     */
    fun openLyrics(coverRect: Rect? = null)

    /** 歌单详情。私密且未解锁时外壳负责先弹密码页。 */
    fun openPlaylist(id: String)
    fun openEditor()

    /**
     * 播放队列页（原版 `MainUI.gd:711-718 open_queue()` → `QueueScreen.gd`）。
     *
     * **「播放列表」＝正在播放的队列**，不是首页那些自建歌单 —— 用户 m06483/m06486
     * 的原话就是「播放列表在哪？」。入口有两处：播放页进度行右端的队列钮、
     * 详情页贴底条「播放列表 · N 首 ›」（原版 `UIFactory._build_detail_bar()`）。
     */
    fun openQueue()

    /** 歌曲操作面板（长按歌曲行）。 */
    fun showSongActions(song: Track)

    /**
     * 歌曲行右侧的「+」：**把这一首追加到当前播放队列末尾，不打断当前播放**
     * （`AddToQueue.gd:133 add()` → `PlayerManager.gd:344 add_to_queue()`）。
     *
     * 「播放列表」＝**正在播放的队列**（`PlayerManager.playlist`），不是 `Playlists` 里的
     * 自建歌单 —— 后者另走长按菜单。行为由用户当面选定：当前这首继续播，轮到新加的那首时
     * 走向（随机 / 单曲循环 / 列表循环 / 单次）一律交给播放器现成的逻辑，这里不自己实现。
     *
     * 加完会弹一条短提示：`已加入播放列表` / `播放列表里已经有了` / `本地歌曲加不进去`。
     */
    fun addToQueue(song: Track)

    /**
     * 底部短提示浮层（原版 `AddToQueue.gd` 的 `AddToQueueToast`）。
     *
     * [accent] ＝ true 用强调色（成功），false 用次要文字色（中性提示）。
     * 1.6 秒后自己消失，同屏只留一条。
     */
    fun showToast(text: String, accent: Boolean = false)

    /** 「页面被吃了」页：密码连错三次时弹。 */
    fun openEaten()

    /**
     * 用系统浏览器打开外部链接（设置页的「作者主页」）。
     * Android 上必须走 Context.startActivity，数据层与页面都够不着，所以挂在外壳上。
     * 打开失败（没有浏览器）时静默吞掉，不弹崩溃。
     */
    fun openUrl(url: String)

    /** 返回键/返回手势。返回 true 表示已被消费，系统不该退出 Activity。 */
    fun handleBack(): Boolean

    companion object {
        const val PAGE_HOME = 0
        const val PAGE_SEARCH = 1
        const val PAGE_LIBRARY = 2
        const val PAGE_SETTINGS = 3
    }
}

val LocalShell = staticCompositionLocalOf<Shell> { error("Shell 未提供") }
