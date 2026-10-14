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
     * 新建歌单，且**建好后把这句 [song] 放进去**（原版 `ActionSheet.gd:248-258` 的
     * `_pending_add_after_create`）。首页那条「新建歌单」入口没有待加入的歌，走 [openEditor]。
     */
    fun openEditorFor(song: Track)

    /** 歌曲操作面板（长按歌曲行）。 */
    fun showSongActions(song: Track)

    /**
     * 歌曲行右侧的「+」：选一个歌单把这首加进去，**加完就把该歌单排成队列、从这首开始放**，
     * 并沿用当前播放模式（不重置随机/单曲/列表）。
     *
     * 为什么不复用 [showSongActions]：那条路是「长按 → 二级面板 → 添加」，加完就收工，
     * 不会起播；用户要的是「加进去并且立刻照当前模式听起来」。
     */
    fun addToPlaylist(song: Track)

    /**
     * 歌曲行右侧「+」的**播放列表**入口（用户 m05292 项⑤⑥）：选一个可保存的待播队列，
     * 把歌放进去并**立刻按当前播放模式起播**（沿用随机/单曲/列表，不重置）。
     *
     * 与 [addToPlaylist] 的分工：那边进的是**歌单**（收藏夹，加完只存着）；
     * 这里进的是**播放列表**（队列，加完接着放）。
     */
    fun addToQueueList(song: Track)

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
