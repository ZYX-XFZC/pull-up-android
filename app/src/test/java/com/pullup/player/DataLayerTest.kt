package com.pullup.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.pullup.player.data.Blacklist
import com.pullup.player.data.BlacklistKind
import com.pullup.player.data.Favorites
import com.pullup.player.data.History
import com.pullup.player.data.LocalLibrary
import com.pullup.player.data.LyricLine
import com.pullup.player.data.LyricsParser
import com.pullup.player.data.PlaylistKind
import com.pullup.player.data.Playlists
import com.pullup.player.data.Preferences
import com.pullup.player.data.Quality
import com.pullup.player.data.SearchHistory
import com.pullup.player.data.TasteModel
import com.pullup.player.data.TextGuard
import com.pullup.player.data.Track
import com.pullup.player.data.formatDuration

/**
 * 数据层回归：把 Godot 版逐条移植过来的语义钉住。
 *
 * 这些断言不是「跑通就算」，每条都对应原版源码里的一个具体行为 —— 数值、文案、
 * 排序口径都在断言里写死，改坏了立刻红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataLayerTest {

    private val context get() = RuntimeEnvironment.getApplication()

    // ---- 时长格式 ----------------------------------------------------------

    @Test
    fun `formatDuration 与 Godot 版一致`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("1:00", formatDuration(60_000))
        assertEquals("3:35", formatDuration(215_000))
        // 原版是 "%d:%02d"，毫秒直接截断不进位
        assertEquals("1:01", formatDuration(61_999))
    }

    // ---- 歌词解析 ----------------------------------------------------------

    @Test
    fun `歌词解析 支持一行多标签与元信息行`() {
        val lrc = """
            [ti:测试]
            [ar:某人]
            [00:01.00]第一行
            [00:12.34][01:20.00]重复行
            [00:30.5]短毫秒
        """.trimIndent()
        val lines = LyricsParser.parse(lrc)
        assertEquals(4, lines.size)
        assertEquals(LyricLine(1.0f, "第一行"), lines[0])
        assertEquals(12.34f, lines[1].time, 0.001f)
        assertEquals("重复行", lines[1].text)
        // parse() 的输出是**按时间升序**的，不是按源码出现顺序：
        // 1.0 / 12.34 / 30.5 / 80.0 ⇒ 下标 2 是 30.5、下标 3 才是 80.0
        assertEquals(30.5f, lines[2].time, 0.001f)
        assertEquals(80.0f, lines[3].time, 0.001f)
        assertEquals("重复行", lines[3].text)
    }

    @Test
    fun `歌词定位 用 position+0_05 做容差`() {
        val lines = LyricsParser.parse("[00:01.00]A\n[00:05.00]B\n[00:09.00]C")
        assertEquals(-1, LyricsParser.lineAt(lines, 0.5f))
        assertEquals(0, LyricsParser.lineAt(lines, 1.0f))
        // 0.96 + 0.05 刚好够到 1.0 那一行
        assertEquals(0, LyricsParser.lineAt(lines, 0.96f))
        assertEquals(1, LyricsParser.lineAt(lines, 5.0f))
        assertEquals(2, LyricsParser.lineAt(lines, 60f))
    }

    // ---- 文案消毒 ----------------------------------------------------------

    @Test
    fun `TextGuard 替换表顺序承重`() {
        assertEquals("相关权利归原作者所有", TextGuard.mask("版权归原作者与版权方所有"))
        assertEquals("相关权利", TextGuard.mask("版权"))
        // 长句必须先于短词命中，否则「音乐授权」会被拆成「音乐来源」
        assertEquals("音频来源", TextGuard.mask("音乐授权"))
        assertEquals("来源", TextGuard.mask("授权"))
        assertEquals("暂不可播放或需要会员", TextGuard.mask("无版权或需要会员"))
        assertTrue(TextGuard.leftovers("版权归原作者与版权方所有").isEmpty())
    }

    // ---- 口味模型 ----------------------------------------------------------

    @Test
    fun `语种判定 谚文优先 纯汉字算华语`() {
        assertEquals("ko", TasteModel.detectLanguage("봄날", ""))
        assertEquals("ja", TasteModel.detectLanguage("さくら", ""))
        assertEquals("zh", TasteModel.detectLanguage("晴天", ""))
        assertEquals("en", TasteModel.detectLanguage("Hello World", ""))
        // 谚文一出现就 ko，哪怕同时有汉字（原版口径）
        assertEquals("ko", TasteModel.detectLanguage("서울 晴天", ""))
    }

    @Test
    fun `口味算分 节奏 旋律 器乐 标签`() {
        val weights = mapOf("rhythm" to 100, "melody" to 0, "vocal" to 50)
        val languages = mapOf("zh" to true, "en" to true, "ja" to true, "ko" to true)

        // 节奏权重拉满 + 命中 remix ⇒ +1.0；时长短于 210s 也判节奏
        val rhythm = TasteModel.scoreSong(
            Track(id = 1, name = "Song (Remix)", artist = "A", duration = 180_000),
            weights, languages, emptyList(),
        )
        assertTrue(rhythm.tasteScore > 0.9f)
        assertTrue(rhythm.tasteReason.contains("节奏"))

        // 器乐关键词 ⇒ vocal 维度的 -1.0
        val inst = TasteModel.scoreSong(
            Track(id = 2, name = "夜曲 (伴奏)", artist = "B", duration = 300_000),
            weights, languages, emptyList(),
        )
        assertTrue(inst.tasteReason.contains("器乐"))

        // 语种未勾选 ⇒ -1.2
        val blocked = TasteModel.scoreSong(
            Track(id = 3, name = "Hello", artist = "C", duration = 240_000),
            weights, mapOf("en" to false), emptyList(),
        )
        assertTrue(blocked.tasteReason.contains("语种未勾选"))

        // 标签命中封顶 3 个：name 里含 night / remix / x 三个标签，extra 不命中
        // 明细（weights rhythm=100 ⇒ 因子 1.0，melody=0 ⇒ -1.0，vocal=50 ⇒ 0.0）：
        //   节奏 = 1.0（命中 remix）× 1.0 = 1.0
        //   旋律 = 0.0（duration=0 走「无时长」分支）× -1.0 = 0.0
        //   人声 = 0.35 × 0.0 = 0.0
        //   语种 = en 已勾选 +0.6（detectLanguage("Night Remix") 无汉字有拉丁 ⇒ en）
        //   标签 = 2.0 × min(3, 3) = 6.0
        val tagged = TasteModel.scoreSong(
            Track(id = 4, name = "Night Remix", artist = "x", duration = 0),
            weights, languages, listOf("night", "remix", "x", "extra"),
        )
        assertEquals(7.6f, tagged.tasteScore, 0.01f)
        assertTrue(tagged.tasteReason.contains("标签"))
    }

    @Test
    fun `口味排序 同分保持原序`() {
        val prefs = Preferences(context)
        val songs = listOf(
            Track(id = 1, name = "同分A", artist = "x", duration = 240_000),
            Track(id = 2, name = "同分B", artist = "x", duration = 240_000),
            Track(id = 3, name = "同分C", artist = "x", duration = 240_000),
        )
        val ranked = TasteModel.rankSongs(songs, prefs)
        assertEquals(listOf(1L, 2L, 3L), ranked.map { it.id })
    }

    // ---- 收藏 --------------------------------------------------------------

    @Test
    fun `收藏 最新的在最前 toggle 可撤销`() {
        val favorites = Favorites(context)
        favorites.clear()
        val a = Track(id = 11, name = "A", artist = "x")
        val b = Track(id = 12, name = "B", artist = "x")

        assertTrue(favorites.toggle(a))          // true = 现在已收藏
        assertTrue(favorites.toggle(b))
        assertEquals(listOf(12L, 11L), favorites.list().map { it.id })

        assertFalse(favorites.toggle(a))         // 再点一次就是取消
        assertEquals(listOf(12L), favorites.list().map { it.id })
        assertTrue(favorites.isFavorite(12L))

        favorites.clear()
        assertEquals(0, favorites.count())
    }

    @Test
    fun `收藏 拒绝没有 id 的歌`() {
        val favorites = Favorites(context)
        favorites.clear()
        assertFalse(favorites.toggle(Track(id = 0, name = "无名")))
    }

    // ---- 历史 --------------------------------------------------------------

    @Test
    fun `最近播放 去重置顶 48小时截断 上限100`() {
        val history = History(context)
        history.clear()
        // 必须用**真实墙钟**：prune() 比对的是 System.currentTimeMillis()，
        // 拿未来时间戳当基准的话永远裁不掉（原版同样是 Time.get_unix_time_from_system()）。
        val now = System.currentTimeMillis() / 1000

        history.recordAt(Track(id = 1, name = "A"), now)
        history.recordAt(Track(id = 2, name = "B"), now)
        history.recordAt(Track(id = 1, name = "A"), now)   // 重记要置顶，不是重复

        assertEquals(listOf(1L, 2L), history.list().map { it.id })

        // 超过 48 小时的老条目在读的时候就被 prune 掉
        history.recordAt(Track(id = 3, name = "C"), now - 49 * 3600)
        assertEquals(listOf(1L, 2L), history.list().map { it.id })

        // 上限 100
        history.clear()
        for (i in 1..120) history.recordAt(Track(id = i.toLong(), name = "S$i"), now)
        assertEquals(100, history.count())
        assertEquals(120L, history.list().first().id)
    }

    // ---- 黑名单 ------------------------------------------------------------

    @Test
    fun `黑名单 四种维度与中文理由`() {
        val blacklist = Blacklist(context)
        blacklist.clear()

        val song = Track(id = 900, name = "晴天", artist = "周杰伦", duration = 269_000)

        blacklist.add(BlacklistKind.SONG, "id:900")
        assertTrue(blacklist.isBlocked(song))
        assertTrue(blacklist.reasons(song).any { it.contains("歌曲") })

        blacklist.clear()
        blacklist.add(BlacklistKind.ARTIST, "周杰伦")
        assertTrue(blacklist.isBlocked(song))

        blacklist.clear()
        // 「抒情」表的关键词是 抒情/ballad/情歌，晴天/周杰伦一首都撞不上 —— 换首真命中的
        blacklist.add(BlacklistKind.TAG, "抒情")
        assertTrue(blacklist.isBlocked(Track(id = 901, name = "抒情歌", artist = "周杰伦")))

        blacklist.clear()
        blacklist.add(BlacklistKind.LANGUAGE, "zh")
        assertTrue(blacklist.isBlocked(song))

        // toggle 返回「现在是否已屏蔽」
        blacklist.clear()
        assertTrue(blacklist.toggle(BlacklistKind.SONG, "id:900"))
        assertFalse(blacklist.toggle(BlacklistKind.SONG, "id:900"))
        blacklist.clear()
    }

    @Test
    fun `黑名单 filterSongs 剔掉被挡的`() {
        val blacklist = Blacklist(context)
        blacklist.clear()
        blacklist.add(BlacklistKind.ARTIST, "坏歌手")
        val input = listOf(
            Track(id = 1, name = "好歌", artist = "好歌手"),
            Track(id = 2, name = "坏歌", artist = "坏歌手"),
        )
        assertEquals(listOf(1L), blacklist.filterSongs(input).map { it.id })
        blacklist.clear()
    }

    @Test
    fun `黑名单 曲风选项来自关键词表`() {
        val blacklist = Blacklist(context)
        blacklist.clear()
        val options = blacklist.optionsFor(Track(id = 1, name = "Rock Anthem", artist = "x"))
        // label 带维度前缀（原版 Blacklist.gd 与 Kotlin 侧一致）：曲风「摇滚」
        assertTrue(options.any { it.label == "曲风「摇滚」" })
        assertTrue(options.any { it.kind == BlacklistKind.SONG })
        assertTrue(options.any { it.kind == BlacklistKind.LANGUAGE })
        blacklist.clear()
    }

    // ---- 歌单 --------------------------------------------------------------

    @Test
    fun `歌单 收藏是投影 日推不可手加 都删不掉`() {
        val favorites = Favorites(context)
        favorites.clear()
        favorites.toggle(Track(id = 21, name = "A"))
        val playlists = Playlists(context, favorites)

        val fav = playlists.favoritesPlaylist()
        assertEquals(PlaylistKind.FAVORITES, fav.kind)
        assertEquals(1, fav.trackCount)
        assertTrue(playlists.isFavorites(fav.id))
        // 投影不参与 addable（不能手动往里加歌），也删不掉
        assertFalse(playlists.addable().any { it.id == fav.id })
        assertFalse(playlists.remove(fav.id))

        val daily = playlists.daily()
        assertEquals("今日推荐歌单", daily.name)
        assertFalse(playlists.addable().any { it.id == daily.id })
        assertFalse(playlists.addSong(daily.id, Track(id = 22, name = "B")))
        assertEquals("「今日推荐歌单」不能手动加歌", playlists.lastError)

        favorites.clear()
    }

    @Test
    fun `歌单 建改删与去重`() {
        val favorites = Favorites(context)
        val playlists = Playlists(context, favorites)
        playlists.userPlaylists().forEach { playlists.remove(it.id) }

        val id = playlists.create("晚上听这些")
        assertTrue(id.isNotBlank())
        assertEquals("晚上听这些", playlists.find(id)?.name)

        val track = Track(id = 301, name = "C")
        assertTrue(playlists.addSong(id, track))
        assertFalse(playlists.addSong(id, track))
        assertEquals("「晚上听这些」里已经有这首了", playlists.lastError)

        playlists.rename(id, "改个名")
        assertEquals("改个名", playlists.find(id)?.name)

        // 名字超 24 字要截断（原版硬截断）
        playlists.rename(id, "字".repeat(40))
        assertEquals(24, playlists.find(id)?.name?.length)

        assertTrue(playlists.remove(id))
        assertEquals(null, playlists.find(id))
    }

    @Test
    fun `歌单 私密只存 sha256 重启不留解锁态`() {
        val favorites = Favorites(context)
        val playlists = Playlists(context, favorites)

        val id = playlists.create("私密的", isPrivate = true, password = "1234")
        assertTrue(playlists.needsPassword(id))
        assertTrue(playlists.isUnlocked(id))          // 建的时候就解锁着
        // 存的不是明文
        assertFalse(playlists.find(id)!!.password.contains("1234"))
        assertEquals(64, playlists.find(id)!!.password.length)

        playlists.lock(id)
        assertFalse(playlists.isUnlocked(id))
        assertFalse(playlists.verify(id, "0000"))
        assertTrue(playlists.verify(id, "1234"))
        assertTrue(playlists.unlock(id, "1234"))
        assertTrue(playlists.isUnlocked(id))

        // 没留密码的「私密」不算锁：needsPassword 要求 private && password 非空
        val id2 = playlists.create("私密但没密码", isPrivate = true)
        assertFalse(playlists.needsPassword(id2))

        playlists.remove(id)
        playlists.remove(id2)
    }

    @Test
    fun `歌单 ensureDaily 整份替换`() {
        val favorites = Favorites(context)
        val playlists = Playlists(context, favorites)
        val songs = (1..5).map { Track(id = it.toLong(), name = "S$it") }
        playlists.ensureDaily(songs)
        assertTrue(playlists.hasDaily())
        assertEquals(5, playlists.daily().trackCount)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), playlists.daily().songs.map { it.id })

        // 再刷一次要整份换掉，不是并集
        playlists.ensureDaily(listOf(Track(id = 9, name = "S9")))
        assertEquals(listOf(9L), playlists.daily().songs.map { it.id })

        // 空列表是有意的守卫（刷新失败别把架子上的歌单擦掉）：调用后原样保留
        playlists.ensureDaily(emptyList())
        assertTrue(playlists.hasDaily())
        assertEquals(listOf(9L), playlists.daily().songs.map { it.id })
    }

    // ---- 本地库 ------------------------------------------------------------

    @Test
    fun `本地库 文件名换算稳定 id 落在负区间`() {
        val library = LocalLibrary(context)
        library.clear()
        assertEquals(0, library.count())

        // 用一个真实文件走一遍导入（内容不是音频也无妨，本层只管文件名与时长缓存）
        val src = java.io.File(context.cacheDir, "probe.mp3")
        src.writeBytes(ByteArray(16))
        val result = library.import(listOf(src.absolutePath))
        assertEquals(1, result.imported)
        assertEquals(1, library.count())

        val track = library.list().first()
        assertTrue(track.local)
        assertEquals("probe", track.name)
        assertEquals("本地文件", track.artist)
        assertEquals("MP3", track.album)
        assertTrue(track.id <= -100_000L && track.id > -190_000L)

        // 再导一次要跳过，而且不算失败
        val again = library.import(listOf(src.absolutePath))
        assertEquals(1, again.skipped.size)
        assertEquals(1, library.count())

        library.clear()
        assertEquals(0, library.count())
    }

    // ---- 搜索历史 ----------------------------------------------------------

    @Test
    fun `搜索历史 去重置顶 上限十条 清空`() {
        val store = SearchHistory(context)
        store.clear()
        assertTrue(store.isEmpty())

        store.remember("周杰伦")
        store.remember("起风了")
        assertEquals(listOf("起风了", "周杰伦"), store.list())

        // 重复的词提到最前，不新增一条（原版 `_remember`：先 erase 再 push_front）。
        store.remember("周杰伦")
        assertEquals(listOf("周杰伦", "起风了"), store.list())
        assertEquals(2, store.list().size)

        // 空词/纯空白直接忽略。
        store.remember("   ")
        assertEquals(2, store.list().size)

        // 超过 10 条踢掉最旧的。
        (1..15).forEach { store.remember("关键词$it") }
        assertEquals(SearchHistory.MAX, store.list().size)
        assertEquals("关键词15", store.list().first())

        // 单条删除与清空。
        store.remove("关键词15")
        assertEquals(SearchHistory.MAX - 1, store.list().size)
        store.clear()
        assertTrue(store.isEmpty())
    }

    @Test
    fun `搜索历史 落盘后重启仍在`() {
        // 同一个 filesDir 上重建一个实例，模拟进程重启。
        SearchHistory(context).apply { clear(); remember("留下来的词") }
        val reopened = SearchHistory(context)
        assertEquals(listOf("留下来的词"), reopened.list())
        reopened.clear()
        assertTrue(SearchHistory(context).isEmpty())
    }

    // ---- 本地库 SAF 导入 ----------------------------------------------------

    @Test
    fun `本地库 SAF 导入 按展示名与扩展名判定`() {
        val library = LocalLibrary(context)
        library.clear()

        // 造三个 content:// 文档：一个合法、一个扩展名不支持、一个重复。
        val good = doc("saf_song.mp3", byteArrayOf(1, 2, 3, 4))
        val bad = doc("saf_cover.png", byteArrayOf(9))

        val r1 = library.importFromUris(listOf(good, bad))
        assertEquals(1, r1.imported)
        assertEquals(listOf("saf_cover.png"), r1.failed)
        assertEquals(1, library.count())
        // 展示名成了曲名，且落进私有目录（分隔符随平台，故分开判目录名）。
        assertEquals("saf_song", library.list().first().name)
        assertTrue(library.list().first().path.contains(LocalLibrary.DIR))

        // 再导同一个：目标已存在算跳过，不算失败。
        val r2 = library.importFromUris(listOf(good))
        assertEquals(0, r2.imported)
        assertEquals(listOf("saf_song.mp3"), r2.skipped)
        assertTrue(r2.failed.isEmpty())
        assertEquals(1, library.count())

        // 打不开的 URI 算失败，且不留下半截文件。
        val missing = android.net.Uri.parse("content://com.pullup.player.test.nope/missing.mp3")
        val r3 = library.importFromUris(listOf(missing))
        assertEquals(0, r3.imported)
        assertEquals(1, r3.failed.size)
        assertEquals(1, library.count())

        library.clear()
        assertEquals(0, library.count())
    }

    /** 造一个可读的「文档」URI。
     *
     * 用 Robolectric 的 `ShadowContentResolver.registerInputStream` 注册一个真正的
     * `content://` 流，模拟 SAF 返回的文档。没有为其注册游标，因此 `displayName`
     * 查不到展示名、会回落到 URI 末段 —— 这正是真实机型上 provider 不吐 DISPLAY_NAME
     * 时的路径，值得覆盖。
     */
    private fun doc(name: String, bytes: ByteArray): android.net.Uri {
        val uri = android.net.Uri.parse("content://com.pullup.player.test.doc/$name")
        org.robolectric.Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, java.io.ByteArrayInputStream(bytes))
        return uri
    }

    // ---- 偏好 --------------------------------------------------------------

    @Test
    fun `偏好 默认值与档位表`() {
        val prefs = Preferences(context)
        assertEquals(3, Preferences.KINDS.size)
        assertEquals(50, Preferences.DEFAULT_WEIGHT)
        // 默认档是 exhigh（320k），对应旧表里的「极高」位置。
        assertEquals("exhigh", Preferences.QUALITY_LEVELS[Preferences.DEFAULT_QUALITY].first)
        assertEquals(320_000, Preferences.QUALITY_LEVELS[Preferences.DEFAULT_QUALITY].second)
        assertEquals(listOf("zh", "en", "ja", "ko"), Preferences.LANGUAGE_CODES)
        assertNotNull(prefs.sourceServer())
        // 逐字对齐原版 `scripts/Preferences.gd:291` / `:347`（用户 m02624：重构不改行为）。
        assertTrue(prefs.darkModeLabel() == "已开启" || prefs.darkModeLabel() == "已关闭")
        assertTrue(prefs.bootAnimationLabel() == "开" || prefs.bootAnimationLabel() == "关")
    }

    /**
     * 音质档位表（用户 m01767 逐字指定）。
     *
     * 这张表是「切音质」功能的唯一真源，标签必须与用户给的用词一字不差：
     * standard→标准、exhigh→exhigh（他确实写的就是标识符）、lossless→无损、
     * hires→Hi-Res、jyeffect→高清环绕声、sky→沉浸环绕声、jymaster→高清母带。
     */
    @Test
    fun `音质 档位名与可实现性`() {
        assertEquals(
            listOf("标准", "较高", "exhigh"),
            Quality.LEVELS.filter { it.implemented }.map { it.label },
        )
        // 用户点名的六档必须都在表里且用词正确
        assertEquals("标准", Quality.byKey(Quality.STANDARD).label)
        assertEquals("exhigh", Quality.byKey(Quality.EXHIGH).label)
        assertEquals("无损", Quality.byKey(Quality.LOSSLESS).label)
        assertEquals("Hi-Res", Quality.byKey(Quality.HIRES).label)
        assertEquals("高清环绕声", Quality.byKey(Quality.JYEFFECT).label)
        assertEquals("沉浸环绕声", Quality.byKey(Quality.SKY).label)
        assertEquals("高清母带", Quality.byKey(Quality.JYMASTER).label)

        // 实测只有前三档能真下发，其余一律标未实现 —— 不能假装能切
        assertTrue(Quality.byKey(Quality.STANDARD).implemented)
        assertTrue(Quality.byKey(Quality.EXHIGH).implemented)
        assertFalse(Quality.byKey(Quality.LOSSLESS).implemented)
        assertFalse(Quality.byKey(Quality.HIRES).implemented)
        assertFalse(Quality.byKey(Quality.JYEFFECT).implemented)
        assertFalse(Quality.byKey(Quality.SKY).implemented)
        assertFalse(Quality.byKey(Quality.JYMASTER).implemented)

        // 服务端回包的 level 字段要能翻成中文名（降级提示靠它）
        assertEquals("exhigh", Quality.servedLabel("exhigh"))
        assertEquals("标准", Quality.servedLabel("standard"))
        assertEquals("", Quality.servedLabel(""))
        // 认不出的档位原样回传，便于排查而不是显示成空白
        assertEquals("unknownlvl", Quality.servedLabel("unknownlvl"))

        assertEquals(Preferences.DEFAULT_QUALITY, Quality.DEFAULT_INDEX)
    }

    @Test
    fun `偏好 码率向下取档 速度钳位`() {
        val prefs = Preferences(context)
        // 按码率反查档位下标。注意现在的表里有 jyeffect/sky/jymaster 这些**码率为 0** 的档位，
        // 反查时必须跳过它们，否则任何码率都会命中 0 档。
        assertEquals(3, prefs.bitrateIndexFor(999_000))
        assertEquals(2, prefs.bitrateIndexFor(320_000))
        assertEquals(1, prefs.bitrateIndexFor(192_000))
        assertEquals(0, prefs.bitrateIndexFor(128_000))
        // 未知码率向下取，取不到就是标准档
        assertEquals(0, prefs.bitrateIndexFor(96_000))

        prefs.setSpeed(99f)
        assertTrue(prefs.speed() <= 3.0f)
        prefs.setSpeed(0.01f)
        assertTrue(prefs.speed() >= 0.25f)
    }

    @Test
    fun `偏好 身份码往返`() {
        val prefs = Preferences(context)
        val code = prefs.exportCode()
        // 前缀是 "PU1-"，与 Godot 版一致（此处不引私有常量，直接对字面量断言）
        assertTrue(code.startsWith("PU1-"))
        assertEquals("", prefs.importCode(code))          // 空串＝导入成功
        assertTrue(prefs.importCode("NOPE-123").isNotEmpty())
        assertTrue(prefs.importCode("PU1-@@@@").isNotEmpty())
    }

    @Test
    fun `偏好 标签与语种`() {
        val prefs = Preferences(context)
        prefs.tags().toList().forEach { prefs.removeTag(it) }

        assertEquals("标签不能为空", prefs.addTag(""))
        assertEquals("标签最多 ${Preferences.MAX_TAG_LENGTH} 个字", prefs.addTag("字".repeat(17)))
        assertEquals("", prefs.addTag("夜晚"))
        assertEquals("已经有这个标签了", prefs.addTag("夜晚"))
        prefs.removeTag("夜晚")
        assertFalse(prefs.tags().contains("夜晚"))

        for (i in 1..20) prefs.addTag("t$i")
        assertEquals(Preferences.MAX_TAGS, prefs.tags().size)
        prefs.tags().toList().forEach { prefs.removeTag(it) }
    }
}
