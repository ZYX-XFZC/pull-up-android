package com.pullup.player

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.pullup.player.data.PlaylistKind
import com.pullup.player.data.Track
import com.pullup.player.player.PlaybackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 整棵界面的冒烟回归：在 JVM 上按真实 Android 框架起 Activity、装 Compose 树、点按导航。
 *
 * 这台机器没有硬件加速（`HypervisorPresent=False`，非管理员也装不了 WHPX/HAXM），x86_64
 * 系统镜像起不来，且没有真机接入 —— 所以「跑起来看一眼」这条路由本测试承担：页面能不能
 * 组合、文案在不在、点下去会不会崩，全都拿得到可复核的结论。
 *
 * 两个刻意的写法：
 * - **不调 `rule.setContent`**：MainActivity 已经在 onCreate 里装了 `PullUpRoot`，再调一次
 *   会抛「setContent called twice」。数据层用的是 StateFlow，测试里改数据会自动重组。
 * - **文案断言统一走 `hasText`**（查 onAllNodes 是否非空），不用 `onNodeWithText`：像
 *   「音乐库」这种字既在底栏又在页面标题上，`onNode` 会因为命中多个而抛歧义。
 *
 * 一条踩过的坑：Robolectric 默认窗口只有 **320×470**，比这个 App 的设计画布（1080×2160
 * 像素 ÷ 2.5 = 432×864dp）小得多 —— 首页的歌单架会被挤到屏幕外，那一段的语义节点被祖先
 * 裁剪成零矩形（`boundsInRoot` 全 0），而 `performClick` 是按坐标注入真实触摸的，于是「点
 * 了没反应」。所以这里把窗口钉到设计尺寸，别用默认值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w432dp-h864dp-xxhdpi", application = PullUpApp::class)
class ShellSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var app: com.pullup.player.data.AppContainer

    @Before
    fun setUp() {
        app = (rule.activity.application as PullUpApp).container
        // 播放会话是**落盘**的（`filesDir/play_session.json`），上一条用例留下的队列会被
        // 壳层的 `restoreSession()` 读回来 —— 那样迷你条上显示的是上一首的歌名而不是
        // 「未在播放」，后面依赖空态的用例就全错位了。每个用例开头清一次队列。
        app.playback.clearQueue()
        // 每个用例都从干净状态开始，免得互相污染
        app.playlists.lockAll()
        app.favorites.clear()
        app.history.clear()
        app.blacklist.clear()
        app.localLibrary.clear()
        app.playlists.userPlaylists().forEach { app.playlists.remove(it.id) }
        rule.waitForIdle()
    }

    private fun hasText(text: String, substring: Boolean = false): Boolean =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    /**
     * 播放页开着的时候界面**永远不 idle**（`PlayerPage` 有个 250ms 的高亮轮询、
     * 播放中有 withFrameNanos 的波形动画），Espresso 会一直等到超时。
     *
     * `fetchSemanticsNode()` 内部会等 idle 才拍语义树快照，所以光换断言函数不够 ——
     * 必须把主时钟的 autoAdvance 关掉，再手动推 600ms 帧（浮层的进场动画是 260ms，
     * 不推帧它就永远停在 `AnimatedVisibility` 的初始不可见状态），断言完恢复。
     */
    private fun hasTextNoIdle(text: String, substring: Boolean = false): Boolean {
        val clock = rule.mainClock
        val previous = clock.autoAdvance
        clock.autoAdvance = false
        return try {
            clock.advanceTimeBy(600)
            rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        } finally {
            clock.autoAdvance = previous
        }
    }

    private fun dumpText(): String {
        val clock = rule.mainClock
        val previous = clock.autoAdvance
        clock.autoAdvance = false
        return try {
            clock.advanceTimeBy(600)
            val root = rule.onRoot().fetchSemanticsNode()
            val sb = StringBuilder()
            fun walk(node: androidx.compose.ui.semantics.SemanticsNode, depth: Int) {
                if (androidx.compose.ui.semantics.SemanticsProperties.Text in node.config) {
                    val texts = node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                        as? List<androidx.compose.ui.text.AnnotatedString> ?: emptyList()
                    texts.forEach { t ->
                        sb.append("  ".repeat(depth))
                        sb.append("[${t.text}]")
                        sb.append('\n')
                    }
                }
                node.children.forEach { walk(it, depth + 1) }
            }
            walk(root, 0)
            if (sb.isEmpty()) sb.append("(语义树无任何 Text 节点 —— 播放页/浮层未组合)")
            sb.toString()
        } finally {
            clock.autoAdvance = previous
        }
    }

    private fun hasDesc(desc: String): Boolean =
        rule.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()

    private fun assertText(text: String) = assertTrue("界面上找不到文案：$text", hasText(text))

    /** 文案在**多行文本节点**里时用这个（`onAllNodesWithText` 默认要整串相等）。 */
    private fun assertTextContains(text: String) =
        assertTrue("界面上找不到含该片段的文案：$text", hasText(text, substring = true))

    private fun clickText(text: String) = rule.onAllNodesWithText(text).onFirst().performClick()

    private fun clickDesc(desc: String) {
        assertTrue("界面上找不到节点：$desc", hasDesc(desc))
        rule.onAllNodesWithContentDescription(desc).onFirst().performClick()
        rule.waitForIdle()
    }

    /**
     * 点一个节点但**不等 idle**。
     *
     * 加进空队列这一下会真的放起来（原版 `PlayerManager.gd:347-349`：队列空就 `play_song`），
     * 而在播那一行的律动条是 `withFrameNanos` 无限循环（`ui/components/Components.kt:348`），
     * 界面从此永远不 idle —— 和播放页同理（见 [hasTextNoIdle]）。所以要关掉主时钟再点。
     */
    private fun clickDescNoIdle(desc: String) {
        val clock = rule.mainClock
        val previous = clock.autoAdvance
        clock.autoAdvance = false
        try {
            clock.advanceTimeBy(600)
            rule.onAllNodesWithContentDescription(desc).onFirst().performClick()
            clock.advanceTimeBy(600)
        } finally {
            clock.autoAdvance = previous
        }
    }

    private fun pressBack() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    // ---- 首页 -------------------------------------------------------------

    @Test
    fun `首页把该有的块都铺出来了`() {
        assertText("Pull Up")
        assertText("YOUR SOUND, YOUR SPACE")
        assertText("今晚，听点什么？")
        assertText("搜索歌曲、歌手或专辑")
        assertText("刷新歌单")
        assertText("新建歌单")
        // 底部三个 Tab（搜索不是 Tab）
        assertText("首页")
        assertText("音乐库")
        assertText("设置")
        // 迷你条：还没播歌时的兜底文案
        assertText("未在播放")
        assertText("选一首歌开始播放")
    }

    @Test
    fun `首页拿不到日推时给中文提示而不是空白`() {
        // 无网络环境下 getRecommend 会失败，首页必须显示可读文案或空态提示，
        // 而不是一片空白（这正是 Godot 版踩过的坑）。
        rule.waitUntil(timeoutMillis = 20_000) {
            hasText("还没有今日推荐，点下面的「刷新歌单」试试") ||
                hasText("每日推荐加载失败", substring = true) ||
                hasText("今日推荐歌单")
        }
    }

    // ---- Tab 切换 ---------------------------------------------------------

    @Test
    fun `底栏能切到音乐库与设置`() {
        clickText("音乐库")
        assertText("LIBRARY")

        clickText("设置")
        assertText("SETTINGS")
        assertText("外观设置")
        assertText("口味偏好")
        assertText("作者主页  ›")

        clickText("首页")
        assertText("今晚，听点什么？")
    }

    // ---- 搜索 -------------------------------------------------------------

    @Test
    fun `首页搜索框推入搜索页并能返回`() {
        clickText("搜索歌曲、歌手或专辑")
        assertText("SEARCH")
        assertText("找点什么？")
        // 历史为空时原版直接铺空态、不显示「最近搜索」标题
        // （`SearchScreen.gd:348-352` 在 `_history.is_empty()` 时 return）。
        // 注意空态在 Kotlin 侧是 MessageCard 的**一个多行文本节点**
        // （"输入关键词开始搜索\n歌手/ 歌名 / 专辑名都可以\n搜过的词会留在历史里"），
        // 所以只能按子串匹配。
        assertTextContains("输入关键词开始搜索")

        pressBack()
        assertText("今晚，听点什么？")
    }

    // ---- 播放页 -----------------------------------------------------------

    @Test
    fun `点迷你条能打开播放页 该有的块都在`() {
        clickText("未在播放")
        assertText("正在播放")
        assertText("选择一首歌开始播放")
        assertText("当前音质：exhigh")
        assertText("0:00")
    }

    /**
     * 真机同场景复现：用户报「播放页打不开」时设备上是**有歌在播**的（track != null），
     * 而上面那条用例走的是 track == null 分支（迷你条兜底文案「未在播放」）。带歌时
     * PlayerPage 才会真正执行背景模糊、歌词抓取、封面 AsyncImage、波形动画等 LaunchedEffect，
     * 这条路径此前从没被 JVM 测试覆盖过。
     *
     * 这里先让一首歌进播放队列并起播（current 非空），再点迷你条进播放页，断言页面能组合、
     * 关键块都在、且不崩。如果「带歌打不开」是真 bug，这条会红并给出堆栈。
     */
    @Test
    fun `有歌在播时 点迷你条也能打开播放页 且不崩`() {
        app.playback.playSong(
            Track(
                id = 702,
                name = "正在播的歌",
                artist = "某歌手",
                cover = "https://example.com/cover702.jpg",
            ),
            emptyList(),
        )
        rule.waitForIdle()
        assertEquals(702L, app.playback.current.value?.id)

        // 迷你条现在显示歌名，点整条（与真机点条行为一致）进播放页
        clickText("正在播的歌")
        // 浮层进场是 fadeIn(260)+slideInVertically，且播放页永远不 idle，用 noIdle 版断言
        val tree = dumpText()
        if (!hasTextNoIdle("正在播放")) {
            org.junit.Assert.fail("播放页没打开。当前语义树文案：$tree")
        }
        assertTrue(hasTextNoIdle("正在播的歌"))
        assertTrue(hasTextNoIdle("某歌手"))
        // 当前音质行（实际文案是「当前音质：标准/高品…」带档位值，按子串匹配）
        assertTrue(hasTextNoIdle("当前音质", substring = true))
        assertTrue(hasTextNoIdle("♪ 音乐已就绪") || hasTextNoIdle("♪ 歌词加载中…") || hasTextNoIdle("♪ 暂无歌词"))
    }

    /**
     * 歌词页（用户 m02420 的沉浸字幕 + 封面飞入补间）。播着歌点歌词槽进得去，
     * 且没有歌时也进得去（原版 `LyricsScreen.gd` 的空态文案）。
     */
    @Test
    fun `点播放页的歌词槽能打开歌词页`() {
        clickText("未在播放")
        // 歌词槽是 clickable 的文字区，没歌时显示「♪ 暂无歌词」。
        clickText("♪ 暂无歌词")
        // 浮层进场是 fadeIn(260)+slideInVertically，不推帧断言不到。
        assertTrue(hasTextNoIdle("未在播放"))
        assertTrue(hasTextNoIdle("暂无歌词") || hasTextNoIdle("歌词加载中"))
        // 系统返回键收掉这一层，回播放页。
        pressBack()
        assertText("正在播放")
    }

    @Test
    fun `播放页设置面板 音源禁用且标注未实现`() {
        clickText("未在播放")
        clickDesc("播放设置")
        assertText("播放速度")
        assertText("定时关闭")
        assertText("音源")
        assertText("该功能暂未实现")
        assertText("变速会同时变调")
        assertText("到点自动停止播放")
        // 循环次数只在「单次播放」模式下才出现（默认是歌单循环）
        assertFalse(hasText("循环次数"))
    }

    @Test
    fun `切到单次播放后 循环次数行才出现`() {
        clickText("未在播放")
        clickDesc("播放设置")
        assertFalse(hasText("循环次数"))
        app.playback.setPlayMode(PlaybackController.ONCE)
        rule.waitForIdle()
        assertText("循环次数")
    }

    /**
     * 音质菜单必须把**拿不到的档位也列出来并标「未实现」**（用户 m01767 逐字要求
     * 「如果不能解析那就标记未实现」），而不是藏起来假装没有。
     */
    @Test
    fun `音质菜单列出全部档位 拿不到的标未实现`() {
        clickText("未在播放")
        clickText("当前音质：exhigh")

        assertText("标准")
        // 当前档带 ✓ 前缀
        assertText("✓ exhigh")
        // 用户点名的六档里，实测服务端只回前三档，其余必须显式标注
        assertText("无损（未实现）")
        assertText("Hi-Res（未实现）")
        assertText("高清环绕声（未实现）")
        assertText("沉浸环绕声（未实现）")
        assertText("高清母带（未实现）")
    }

    // ---- 歌单 -------------------------------------------------------------

    @Test
    fun `自建歌单出现在首页横滑架 且带删除入口`() {
        val id = app.playlists.create("测试歌单")
        app.playlists.addSong(id, Track(id = 501, name = "某首歌", artist = "某人"))
        rule.waitForIdle()

        assertText("我的歌单")
        assertText("测试歌单")
        assertText("1 首")
        assertTrue(hasDesc("删除歌单"))
    }

    @Test
    fun `我的收藏是伪歌单 没有删除入口`() {
        app.favorites.toggle(Track(id = 601, name = "收藏的歌", artist = "x"))
        rule.waitForIdle()

        assertText("我的收藏")
        // 收藏是 Favorites 的投影，不是真歌单，不该有删除入口
        assertFalse(hasDesc("删除歌单"))
        assertEquals(PlaylistKind.FAVORITES, app.playlists.favoritesPlaylist().kind)
    }

    @Test
    fun `删自建歌单要走确认弹窗 点错不删`() {
        val id = app.playlists.create("待删歌单")
        rule.waitForIdle()

        clickDesc("删除歌单")
        // 逐字文案，标点与颜文字不许改
        assertText("确认删掉我吗，｡ﾟ(ﾟ´Д`ﾟ)ﾟ｡")
        assertText("我点错了")

        clickText("我点错了")
        assertNotNull("取消之后歌单不该消失", app.playlists.find(id))

        clickDesc("删除歌单")
        clickText("是")
        assertEquals(null, app.playlists.find(id))
    }

    @Test
    fun `私密歌单删除要连按三次 第三次换文案`() {
        val id = app.playlists.create("私密歌单", isPrivate = true, password = "1234")
        rule.waitForIdle()

        clickDesc("删除歌单")
        assertText("确认删掉我吗，｡ﾟ(ﾟ´Д`ﾟ)ﾟ｡")

        clickText("是")
        // 第 2 步：文案不变，面板不收起，歌单还在
        assertText("确认删掉我吗，｡ﾟ(ﾟ´Д`ﾟ)ﾟ｡")
        assertNotNull(app.playlists.find(id))

        clickText("是")
        // 第 3 步换成「还会见面吗」那一组
        assertText("还会见面吗(｡•́︿•̀｡)")
        assertText("是...")
        assertText("我后悔了！")
        assertNotNull(app.playlists.find(id))

        clickText("是...")
        assertEquals(null, app.playlists.find(id))
    }

    @Test
    fun `私密歌单点进去先要密码`() {
        val id = app.playlists.create("上锁的歌单", isPrivate = true, password = "1234")
        app.playlists.lock(id)
        rule.waitForIdle()

        clickText("上锁的歌单")
        // 密码页而不是歌单详情（原版 _on_playlist_activated 的分支）
        assertFalse(hasText("播放全部"))
    }

    @Test
    fun `解锁后点歌单进的是详情页`() {
        val id = app.playlists.create("开着的歌单")
        app.playlists.addSong(id, Track(id = 801, name = "里面的歌", artist = "y"))
        rule.waitForIdle()

        clickText("开着的歌单")
        assertTrue("歌单详情页该有「播放全部」", hasText("播放全部", substring = true))
        assertText("里面的歌")
    }

    // ---- 播放行为 ---------------------------------------------------------

    @Test
    fun `播放控制不崩且队列状态可读`() {
        app.playback.playSong(Track(id = 701, name = "测试歌", artist = "测试歌手"), emptyList())
        rule.waitForIdle()
        assertEquals(701L, app.playback.current.value?.id)
        assertEquals(1, app.playback.queue.value.size)

        // 这些调用都只在状态机层面走一遍；网络取址必然失败，失败也必须被引擎吃掉而不是崩
        app.playback.togglePlayPause()
        app.playback.playNext()
        app.playback.playPrevious()
        app.playback.setPlayMode(PlaybackController.SINGLE_LOOP)
        app.playback.setShuffle(true)
        app.playback.seek(1_000)
        app.playback.stop()
        rule.waitForIdle()
        assertNotNull(app.playback.current.value)
    }

    @Test
    fun `三种播放模式循环切换`() {
        app.playback.setPlayMode(PlaybackController.LIST_LOOP)
        assertEquals(PlaybackController.LIST_LOOP, app.playback.playMode.value)
        app.playback.cyclePlayMode()
        assertEquals(PlaybackController.SINGLE_LOOP, app.playback.playMode.value)
        app.playback.cyclePlayMode()
        assertEquals(PlaybackController.ONCE, app.playback.playMode.value)
        app.playback.cyclePlayMode()
        assertEquals(PlaybackController.LIST_LOOP, app.playback.playMode.value)
    }

    @Test
    fun `定时关闭能起能停`() {
        app.playback.startSleepTimer(15)
        assertEquals(15, app.playback.sleepLeft.value)
        app.playback.stopSleepTimer()
        assertEquals(0, app.playback.sleepLeft.value)
    }

    // ---- 主题 -------------------------------------------------------------

    @Test
    fun `深浅模式与面板质感切换不崩`() {
        val before = app.preferences.darkMode()
        app.preferences.toggleDarkMode()
        rule.waitForIdle()
        assertFalse(before == app.preferences.darkMode())
        app.preferences.toggleDarkMode()

        app.preferences.cyclePanelStyle()
        rule.waitForIdle()
        assertTrue(app.preferences.panelStyleName() in listOf("亚克力", "云母", "纯色"))
        app.preferences.cycleMotionScale()
        rule.waitForIdle()
        assertTrue(app.preferences.motionScale() > 0f)
    }

    // ---- 设置页里的两页 ---------------------------------------------------

    @Test
    fun `设置页能开外观设置页`() {
        clickText("设置")
        clickText("外观设置")
        rule.waitUntil(timeoutMillis = 5_000) { hasText("外观", substring = true) }
        pressBack()
        assertText("SETTINGS")
    }

    @Test
    fun `外观页的两项开关是滑动开关 点行即切换`() {
        clickText("设置")
        clickText("外观设置")
        rule.waitUntil(timeoutMillis = 5_000) { hasText("外观", substring = true) }

        // 用户 m05292 项①：深色模式 / 启动动画从「文字值」改成滑动开关。
        // 开关是自绘 Canvas + semantics，读屏文案只有「已开启 / 已关闭」两档，
        // 所以两行各点一次 = 各切一次，值必须与点击前相反。
        val darkBefore = app.preferences.darkMode()
        val bootBefore = app.preferences.bootAnimation()

        // 每行有两个可点节点（整行 + 开关），点整行即可（onClick 与开关同一个 setter）。
        clickText("深色模式")
        rule.waitUntil(timeoutMillis = 5_000) { app.preferences.darkMode() != darkBefore }
        clickText("启动动画")
        rule.waitUntil(timeoutMillis = 5_000) { app.preferences.bootAnimation() != bootBefore }

        // 再点一次回原样：确认是 toggle 而不是一次性开关。
        clickText("深色模式")
        rule.waitUntil(timeoutMillis = 5_000) { app.preferences.darkMode() == darkBefore }
    }

    @Test
    fun `设置页能开口味偏好页`() {
        clickText("设置")
        clickText("口味偏好")
        rule.waitUntil(timeoutMillis = 5_000) { hasText("节奏感", substring = true) }
    }

    // ---- 返回键分层 -------------------------------------------------------

    @Test
    fun `返回键先回首页 再按才提示退出`() {
        clickText("设置")
        assertText("SETTINGS")

        // ① 非首页 → 回首页
        pressBack()
        assertText("今晚，听点什么？")

        // ② 已在首页 → 第一次只弹提示，不退出
        pressBack()
        assertText("再按一次返回键退出")
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun `浮层打开时返回键先收浮层 页面还在`() {
        clickText("未在播放")
        assertText("正在播放")

        pressBack()
        assertText("今晚，听点什么？")
        assertFalse(rule.activity.isFinishing)
    }

    // ---- 播放队列（原版 `AddToQueue.gd` / `PlayerManager.gd:344`）-------------

    @Test
    fun `歌曲行的加号是直接加进播放队列 不再弹选择器`() {
        // 先造一首听过的歌：加号只在「最近播放」列表行上（首页没有歌曲行）。
        app.history.record(Track(id = 1001L, name = "冒烟加号", artist = "甲", album = "乙"))
        clickText("音乐库")
        rule.waitForIdle()
        assertText("最近播放")

        assertTrue("歌曲行右侧应该有「+」", hasDesc("加入播放列表"))
        clickDescNoIdle("加入播放列表")

        // 老行为（弹「把这首放进哪个播放列表？」选择器）必须消失。
        assertFalse("不该再弹播放列表选择器", hasTextNoIdle("把这首放进哪个播放列表？"))

        // 新行为：歌直接进了播放队列。
        assertEquals(1, app.playback.getQueueSize())
        assertEquals(1001L, app.playback.queue.value.first().id)
    }

    @Test
    fun `同一首歌不会重复加进播放队列`() {
        app.history.record(Track(id = 1002L, name = "重复加号", artist = "甲", album = "乙"))
        clickText("音乐库")
        rule.waitForIdle()

        clickDescNoIdle("加入播放列表")
        assertEquals(1, app.playback.getQueueSize())

        // 再点一次：按 id 判重，队列长度不变（原版 `PlayerManager.gd:350-351`）。
        clickDescNoIdle("加入播放列表")
        assertEquals(1, app.playback.getQueueSize())
    }
}
