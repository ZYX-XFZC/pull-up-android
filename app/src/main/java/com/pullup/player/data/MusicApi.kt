package com.pullup.player.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 音乐数据源，移植自 Godot 版 scripts/api/APIHandler.gd。
 *
 * ## 播放地址三级优先级（`getSongUrl`）
 *
 * 1. **MeTing**（`api.qijieya.cn/meting/?type=url&id=`）—— 支持网易云 VIP 解析，首选。
 * 2. **vkeys 第三方**（`api.vkeys.cn/v2/music/netease?id=&quality=`）—— MeTing 无响应时兜底。
 * 3. **网易云官方**（`music.163.com/song/media/outer/url?id=xxx.mp3`）—— 终极兜底，
 *    稳定但**不解析 VIP**（VIP 歌曲只能试听）。
 *
 * ## 元数据 / 歌单 / 搜索
 *
 * 仍走 msapi.awup.cn（`BASE_URL`）：老接口补专辑名、时长、歌手 id，以及推荐/歌单广场/歌手歌单。
 * MeTing 的回包极简（`[{name, artist, url, pic, lrc}]`），歌曲 id 藏在 url 的 query 里，
 * 所以 normalize 要从 url 里把 id 抠出来，并且容忍「单个对象」而不是数组的回包。
 */
class MusicApi(context: Context, private val prefs: Preferences) {

    /**
     * OkHttp 客户端。
     *
     * 关键优化（用户 m01767「加载还是太慢了」）：
     *
     * - **HTTP 磁盘缓存**：实测老接口每个端点都回 `Cache-Control: max-age=120` + `ETag` +
     *   gzip 压缩，以前完全没用上 —— 每次进页面都是全量回源。挂上缓存后，两分钟内的重复
     *   请求（反复进搜索页、切回首页、重开播放页拉歌词）直接命中本地，零往返。
     * - **连接池与并发**：搜索与补全要同时打两个域，默认每主机 5 个连接够用，但把总并发
     *   提到 16、缩短空闲回收，避免多首歌同时解析地址时排队。
     * - **超时收紧**：15 秒是「等死」级别的宽容度，实测所有端点都在 1.5 秒内返回，
     *   收到 8 秒；慢的请求早失败早走兜底链，比让用户盯着转圈好。
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .cache(Cache(File(context.applicationContext.cacheDir, "http_cache"), HTTP_CACHE_BYTES))
        .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply { maxRequests = 16; maxRequestsPerHost = 8 })
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 播放地址缓存：`"id@档位"` → (url, 写入时刻, 该条地址自身的寿命)。
     *
     * 寿命**逐条存**是因为回包可能带 `expi`（实测 1200 秒 = 20 分钟），它是这条 CDN 地址的
     * 真实有效期；以前全表统一按 30 分钟缓存，比真实寿命还长，后 10 分钟必然 403。
     */
    private val urlCache = HashMap<String, CachedUrl>()

    private data class CachedUrl(val url: String, val at: Long, val ttlMs: Long)

    /** 本会话内已取到的歌词（歌曲 id → LRC 文本）。 */
    private val lyricCache = HashMap<Long, String>()

    companion object {
        const val BASE_URL = "https://msapi.awup.cn/"
        const val METING_URL = "https://api.qijieya.cn/meting/"

        /** vkeys 第三方兜底接口基址。 */
        const val VKEYS_URL = "https://api.vkeys.cn/v2/music/netease"

        /** 网易云官方终极兜底接口（不解析 VIP，只给试听）。 */
        const val NETEASE_OFFICIAL_URL = "https://music.163.com/song/media/outer/url?id="

        /** 连接超时：握手慢说明线路有问题，早点切兜底。 */
        const val CONNECT_TIMEOUT_S = 6L

        /** 读超时：实测最慢端点 1.5 秒内返回，留足 8 秒余量。 */
        const val READ_TIMEOUT_S = 8L

        /** 单次调用总上限：含重定向。 */
        const val CALL_TIMEOUT_S = 10L

        /** HTTP 磁盘缓存上限。这些接口的正文都很小（推荐歌单约 70KB），16MB 足够放很久。 */
        const val HTTP_CACHE_BYTES = 16L * 1024 * 1024

        /** 空闲连接上限，多域并发时够用。 */
        const val MAX_IDLE_CONNECTIONS = 8

        /**
         * 地址缓存兜底 TTL，只在回包**没带 `expi`** 时使用。
         */
        const val URL_CACHE_TTL_MS = 30 * 60 * 1000L

        /**
         * 占位歌词（非网易云音源误查网易云 id、纯音乐曲目都会回这些）：有它们就等于没歌词。
         * 原文是「这似乎是一首纯音乐呢，请尽情欣赏它吧！」。
         */
        val LYRIC_PLACEHOLDER_KEYS = listOf("这似乎是一首", "请尽情欣赏", "请欣赏", "纯音乐")
    }

    // -----------------------------------------------------------------------
    // 搜索
    // -----------------------------------------------------------------------

    /**
     * 搜索。先用 MeTing（一次请求就能出结果，比老接口快），
     * MeTing 回空时退回老接口（结果没有可播 url，但播放时会按 id 再解析，照样能播）。
     */
    suspend fun search(keyword: String, limit: Int = 30): Result<List<Track>> {
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val meting = runCatching { getJson(metingUrl("search", enc, "&limit=$limit")) }.getOrNull()
        val fromMeting = metingItems(meting)
        if (fromMeting.isNotEmpty()) return Result.success(fromMeting)

        val fallback = runCatching { getJson("$BASE_URL" + "search?keywords=$enc&limit=$limit") }.getOrNull()
        val songs = normalizeSongs(payload(fallback).opt("result")?.let { asDict(it)?.opt("songs") })
        return if (songs.isEmpty()) Result.failure(ApiException("没有找到相关歌曲"))
        else Result.success(songs)
    }

    /**
     * 元数据补全：专辑 / 时长 / 歌手 id 只有老接口有。先出结果再补，补不到也不影响播放。
     * MeTing 搜索结果本身没有专辑名，所以首页与搜索页都靠它回填。
     */
    suspend fun enrich(songs: List<Track>): List<Track> {
        val ids = songs.map { it.id }.filter { it > 0 }
        if (ids.isEmpty()) return songs
        val json = runCatching { getJson("$BASE_URL" + "song/detail?ids=" + ids.joinToString(",")) }.getOrNull()
            ?: return songs
        val details = normalizeSongs(payload(json).opt("songs"))
        if (details.isEmpty()) return songs
        val byId = details.associateBy { it.id }
        return songs.map { song ->
            val info = byId[song.id] ?: return@map song
            song.copy(
                album = song.album.ifEmpty { info.album },
                duration = if (song.duration <= 0) info.duration else song.duration,
                artistId = if (song.artistId <= 0) info.artistId else song.artistId,
            )
        }
    }

    // -----------------------------------------------------------------------
    // 播放地址
    // -----------------------------------------------------------------------

    /**
     * 取播放地址。**三级优先级回退链**：
     *
     * 1. **MeTing**（`api.qijieya.cn/meting/?type=url&id=`）—— 支持网易云 VIP 解析，首选。
     * 2. **vkeys 第三方**（`api.vkeys.cn/v2/music/netease?id=&quality=`）—— MeTing 无法响应时兜底。
     * 3. **网易云官方**（`music.163.com/song/media/outer/url?id=xxx.mp3`）—— 终极兜底，
     *    稳定但**不解析 VIP**（VIP 歌曲只能试听）。
     *
     * ## 实现要点
     *
     * - **MeTing 走 302**：`type=url` 端点回 302 重定向到 CDN 直链。这里用**不跟随重定向**的
     *   临时 client 主动读 `Location` 头，拿到真实 CDN URL 再缓存 —— 避免每次播放都撞一次跳转。
     * - **vkeys 走 JSON**：回包 `{code:200, data:{url:"...", level:"..."}}`，用短超时（5 秒）避免挂起。
     * - **官方 API 直接拼 URL**：无需请求，交给 Media3 跟随重定向。VIP 歌曲会自动降级到试听。
     * - 所有源的失败（超时、解析失败、URL 为空）都静默吞掉，进入下一级。
     *
     * @param qualityKey 音质档位标识（[Quality.LEVELS] 的 key）。MeTing 忽略它，vkeys 用它换算成 `quality=8/12/16`。
     * @param knownStream MeTing 搜索时预取的代理地址，仅当三级都失败时提示用（一般用不上）。
     */
    suspend fun getSongUrl(
        songId: Long,
        qualityKey: String = Quality.EXHIGH,
        knownStream: String = "",
    ): UrlResult {
        if (songId <= 0) return UrlResult.Fail("歌曲 ID 无效")

        val key = "$songId@$qualityKey"
        cachedUrl(key)?.let { return UrlResult.Ok(it) }

        // ── 优先级 1：MeTing（支持 VIP） ─────────────────────────────────────
        val metingUrl = tryMetingUrl(songId)
        if (metingUrl != null) {
            val secure = secureUrl(metingUrl)
            rememberUrl(key, secure, 0)
            return UrlResult.Ok(secure, "meting")
        }

        // ── 优先级 2：vkeys 第三方 ───────────────────────────────────────────
        val vkeys = tryVkeysUrl(songId, qualityKey)
        if (vkeys != null) {
            val (url, served) = vkeys
            val secure = secureUrl(url)
            rememberUrl(key, secure, 0)
            return UrlResult.Ok(secure, served)
        }

        // ── 优先级 3：网易云官方（终极兜底，不解析 VIP） ────────────────────
        val official = NETEASE_OFFICIAL_URL + songId + ".mp3"
        rememberUrl(key, official, 0)
        return UrlResult.Ok(official, "standard")
    }

    /**
     * 优先级 1：MeTing 地址解析。
     *
     * `/meting/?type=url&id=xxx` 会 302 跳到 CDN 直链。这里用**不跟随重定向**的临时 client
     * 读 `Location` 头，拿到最终直链后缓存。这比让 Media3 每次开播都撞一次 302 更快
     * （实测省 165–405ms）。
     *
     * 返回 null 表示 MeTing 不可用（超时、非 3xx、无 Location 头），交由下一级兜底。
     */
    private suspend fun tryMetingUrl(songId: Long): String? = withContext(Dispatchers.IO) {
        val proxyUrl = metingUrl("url", songId.toString())
        runCatching {
            val noRedirectClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(proxyUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) PullUp/1.0")
                .build()
            noRedirectClient.newCall(request).execute().use { response ->
                when {
                    // 302/301：Location 头里就是 CDN 直链。用 resolve 兜一下相对路径的极端情况。
                    response.isRedirect -> {
                        val loc = response.header("Location") ?: return@use null
                        response.request.url.resolve(loc)?.toString() ?: loc
                    }
                    // 极少数情况 MeTing 直接 200 返回纯文本 URL。
                    response.isSuccessful -> response.body?.string()?.trim()
                        ?.takeIf { it.startsWith("http") }
                    else -> null
                }
            }
        }.getOrNull()
    }

    /**
     * 优先级 2：vkeys 第三方兜底。
     *
     * 请求 `api.vkeys.cn/v2/music/netease?id=xxx&quality=<8|12|16>`，回包：
     * ```json
     * { "code": 200, "data": { "url": "https://...", "level": "exhigh", ... } }
     * ```
     *
     * 用**独立短超时 client**（3s 连接 / 4s 读 / 5s 总）：它的角色是「MeTing 死了之后
     * 快速接手」，不能把用户卡在转圈上。
     *
     * 返回 `(url, served)` —— `served` 是服务端实际下发的档位（vkeys 若无该字段，
     * 就用请求的档位代替）。
     */
    private suspend fun tryVkeysUrl(songId: Long, qualityKey: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val vkeysQuality = Quality.vkeysQuality(qualityKey)
            val url = "$VKEYS_URL?id=$songId&quality=$vkeysQuality"
            runCatching {
                val shortClient = client.newBuilder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                    .callTimeout(5, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) PullUp/1.0")
                    .build()
                shortClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string()?.trim().orEmpty()
                    if (body.isEmpty() || !body.startsWith("{")) return@use null
                    val json = JSONObject(body)
                    if (json.optInt("code", 0) != 200) return@use null
                    val data = json.optJSONObject("data") ?: return@use null
                    // 容错两种字段名：url（主）/ link（备用）
                    val streamUrl = data.optString("url", "")
                        .takeIf { it.isNotBlank() && it != "null" }
                        ?: data.optString("link", "")
                            .takeIf { it.isNotBlank() && it != "null" }
                        ?: return@use null
                    val served = data.optString("level", "")
                        .takeIf { it.isNotBlank() }
                        ?: qualityKey
                    streamUrl to served
                }
            }.getOrNull()
        }

    /**
     * 丢掉某首歌**所有档位**的地址缓存。网易云 CDN 的地址带时间戳 + `vuutv` 签名，
     * 寿命由回包的 `expi` 决定（实测 1200 秒）；缓存已按它判过期，但服务端仍可能提前
     * 作废（换节点、风控），所以播放失败时调这里，下一次解析重新回源拿一条新地址。
     */
    fun invalidateSongUrl(songId: Long) {
        if (songId <= 0) return
        urlCache.keys.filter { it.startsWith("$songId@") }.forEach { urlCache.remove(it) }
    }

    private fun cachedUrl(key: String): String? {
        val entry = urlCache[key] ?: return null
        // 逐条按自己的寿命判过期；ttl 为 0（MeTing/vkeys 兜底那类没有 expi 的）用默认值。
        val ttl = if (entry.ttlMs > 0) entry.ttlMs else URL_CACHE_TTL_MS
        if (SystemClock.elapsedRealtime() - entry.at > ttl) {
            urlCache.remove(key)
            return null
        }
        return entry.url
    }

    /**
     * 记一条地址缓存。
     *
     * [servedExpiSeconds] 是回包自带的 `expi`（秒）。真实寿命要留出安全余量 —— 直接按整段
     * 缓存会在到期边缘拿到刚失效的地址，所以打八折，宁可多解析一次也不要播到一半 403。
     */
    private fun rememberUrl(key: String, url: String, servedExpiSeconds: Int) {
        val ttl = if (servedExpiSeconds > 0) servedExpiSeconds * 1000L * 8 / 10 else URL_CACHE_TTL_MS
        urlCache[key] = CachedUrl(url, SystemClock.elapsedRealtime(), ttl)
    }

    // -----------------------------------------------------------------------
    // 歌词
    // -----------------------------------------------------------------------

    /**
     * 歌词。MeTing 直接返回 LRC 文本（不是 JSON）。
     *
     * 按 音源 → 网易云 → 老接口 三级往下退，而不是一次失败就判死。
     *
     * **为什么要有第 1 级（强制网易云）**：日推/搜索里的歌都是网易云 id，而 MeTing 拿
     * **非网易云音源**去查网易云 id 时会回一句带时间戳的占位符
     * 「[00:00.00]这似乎是一首纯音乐呢，请尽情欣赏它吧！」——它会被解析成真歌词显示出来。
     */
    suspend fun getLyric(songId: Long): Result<Pair<String, String>> {
        if (songId <= 0) return Result.failure(ApiException("歌曲 ID 无效"))
        lyricCache[songId]?.let { return Result.success(it to "") }

        // 第 0 级：设置里的音源。
        var text = fetchLyricText(metingUrl("lrc", songId.toString()))
        if (!isUsable(text)) {
            // 第 1 级：强制网易云。
            text = fetchLyricText(metingUrl("lrc", songId.toString(), "", "netease"))
        }
        if (isUsable(text)) {
            val real = stripPlaceholderLines(text)
            if (real.isNotBlank()) {
                lyricCache[songId] = real
                return Result.success(real to "")
            }
        }

        // 第 2 级：老接口 /lyric。
        // 注意：老接口 /lyric 的回包**不走 payload()**（它与 /album 一样可能不带 code）。
        val json = runCatching { getJson("$BASE_URL" + "lyric?id=$songId") }.getOrNull()
        val obj = asDict(json)
        val lyric = asDict(obj?.opt("lrc"))?.optString("lyric", "").orEmpty()
        val translated = asDict(obj?.opt("tlyric"))?.optString("lyric", "").orEmpty()
        val real = stripPlaceholderLines(lyric)
        if (real.isBlank()) return Result.failure(ApiException("暂无歌词"))
        lyricCache[songId] = real
        return Result.success(real to translated)
    }

    private suspend fun fetchLyricText(url: String): String =
        runCatching { getText(url) }.getOrNull().orEmpty()

    /** 不是歌词：空、JSON 错误体、HTML 错误页。 */
    private fun isUsable(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && !t.startsWith("{") && !t.startsWith("<")
    }

    /** 丢掉只有占位文案的歌词行（保留其它行与时间戳）。等价于 _strip_placeholder_lines()。 */
    fun stripPlaceholderLines(lrc: String): String =
        lrc.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val body = if (line.startsWith("[")) {
                val close = line.indexOf(']')
                if (close >= 0) line.substring(close + 1) else line
            } else line
            if (LYRIC_PLACEHOLDER_KEYS.any { body.contains(it) }) null else line
        }.joinToString("\n")

    // -----------------------------------------------------------------------
    // 歌单 / 歌手 / 专辑
    // -----------------------------------------------------------------------

    /** 歌单曲目也走 MeTing，这样才能拿到可播的 url。 */
    suspend fun getPlaylist(playlistId: Long): Result<Playlist> {
        if (playlistId <= 0) return Result.failure(ApiException("歌单 ID 无效"))
        val json = runCatching { getJson(metingUrl("playlist", playlistId.toString())) }.getOrNull()
        val songs = metingItems(json)
        if (songs.isEmpty()) return Result.failure(ApiException("歌单获取失败（可能是私密歌单）"))
        return Result.success(Playlist(id = playlistId, songs = songs))
    }

    /** 歌手热门歌曲。回包形状与 /song/detail 一致（ar/al/dt）。 */
    suspend fun getArtistSongs(artistId: Long, limit: Int = 50): Result<Pair<String, List<Track>>> {
        if (artistId <= 0) return Result.failure(ApiException("歌手 ID 无效"))
        val json = runCatching {
            getJson("$BASE_URL" + "artist/top/song?id=$artistId&limit=$limit")
        }.getOrNull()
        val songs = normalizeSongs(payload(json).opt("songs"))
        if (songs.isEmpty()) return Result.failure(ApiException("这位歌手的歌曲没取到"))
        val name = songs.firstOrNull { it.artistId == artistId }?.artist
            ?: songs.first().artist
        return Result.success(name to songs)
    }

    /** 歌手专辑（老接口 `artist/album`，回包 `hotAlbums[]`）。 */
    suspend fun getArtistAlbums(artistId: Long, limit: Int = 30): Result<List<Album>> {
        if (artistId <= 0) return Result.failure(ApiException("歌手 ID 无效"))
        val json = runCatching {
            getJson("$BASE_URL" + "artist/album?id=$artistId&limit=$limit")
        }.getOrNull()
        val albums = normalizeAlbums(payload(json).opt("hotAlbums"))
        if (albums.isEmpty()) return Result.failure(ApiException("这位歌手的专辑没取到"))
        return Result.success(albums)
    }

    /**
     * 按专辑 id 取曲目。
     *
     * ⚠️ 老接口的 `album?id=` 回包**不带 `code` 字段**（只有 `resourceState: true` + `songs[]`），
     * 所以这里**不能走 [payload]** —— 它要求 code == 200，会把正常回包判成空 ⇒ 专辑曲目永远加载不出来。
     */
    suspend fun getAlbumSongs(albumId: Long): Result<List<Track>> {
        if (albumId <= 0) return Result.failure(ApiException("专辑 ID 无效"))
        val json = runCatching { getJson("$BASE_URL" + "album?id=$albumId") }.getOrNull() as? JSONObject
            ?: return Result.failure(ApiException("这张专辑的歌没取到"))
        val songs = normalizeSongs(json.opt("songs"))
        return if (songs.isEmpty()) Result.failure(ApiException("这张专辑的歌没取到"))
        else Result.success(songs)
    }

    /** 每日推荐。 */
    suspend fun getRecommend(): Result<List<Track>> {
        val json = runCatching { getJson("$BASE_URL" + "recommend/songs") }.getOrNull()
        val inner = asDict(payload(json).opt("data"))
        val songs = normalizeSongs(inner?.opt("dailySongs"))
        return if (songs.isEmpty()) Result.failure(ApiException("每日推荐获取失败"))
        else Result.success(songs)
    }

    /** 推荐歌单（首页「为你推荐」）。 */
    suspend fun getPersonalized(limit: Int = 6): Result<List<Playlist>> {
        val json = runCatching { getJson("$BASE_URL" + "personalized?limit=$limit") }.getOrNull()
        val playlists = normalizePlaylists(payload(json).opt("result"))
        return Result.success(playlists)
    }

    /** 歌单广场（「热门歌单」）。 */
    suspend fun getTopPlaylists(limit: Int = 20): Result<List<Playlist>> {
        val json = runCatching { getJson("$BASE_URL" + "top/playlist?limit=$limit") }.getOrNull()
        val playlists = normalizePlaylists(payload(json).opt("playlists"))
        return if (playlists.isEmpty()) Result.failure(ApiException("歌单列表获取失败"))
        else Result.success(playlists)
    }

    // -----------------------------------------------------------------------
    // 归一化
    // -----------------------------------------------------------------------

    /**
     * 两种回包形状拍平成一个 [Track]：
     * 搜索接口给 `artists` / `album` / `duration`，song/detail、recommend、playlist/detail 给 `ar` / `al` / `dt`。
     */
    fun normalizeSong(raw: JSONObject?): Track? {
        if (raw == null) return null
        var artistId = 0L
        val names = mutableListOf<String>()
        val artists = raw.opt("artists") ?: raw.opt("ar")
        if (artists is JSONArray) {
            for (i in 0 until artists.length()) {
                val a = artists.optJSONObject(i) ?: continue
                val n = a.optString("name", "")
                if (n.isEmpty()) continue
                names += n
                if (artistId <= 0) artistId = a.optLong("id", 0L)
            }
        }
        val album = asDict(raw.opt("album") ?: raw.opt("al"))
        val duration = raw.optInt("duration", raw.optInt("dt", 0))
        return Track(
            id = raw.optLong("id", 0L),
            name = raw.optString("name", "Unknown"),
            artist = if (names.isNotEmpty()) names.joinToString(", ") else "Unknown Artist",
            artistId = artistId,
            album = album?.optString("name", "").orEmpty(),
            cover = secureUrl(album?.optString("picUrl", "").orEmpty()),
            duration = duration,
        )
    }

    fun normalizeSongs(raw: Any?): List<Track> {
        val array = raw as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { normalizeSong(array.optJSONObject(it)) }
    }

    /** MeTing 返回 `[{name, artist, url, pic, lrc}]`；只有一首时也可能返回**单个对象**。 */
    private fun metingItems(data: Any?): List<Track> = when (data) {
        is JSONArray -> (0 until data.length()).mapNotNull { normalizeMeting(data.optJSONObject(it)) }
        is JSONObject -> listOfNotNull(normalizeMeting(data))
        else -> emptyList()
    }

    private fun normalizeMeting(raw: JSONObject?): Track? {
        if (raw == null) return null
        val stream = secureUrl(raw.optString("url", ""))
        val songId = idFromUrl(stream)
        if (songId <= 0) return null
        // MeTing 用 "/" 分隔多个歌手（"蔡依林/周杰伦"）。
        val artist = raw.optString("artist", "").replace("/", ", ")
        return Track(
            id = songId,
            name = raw.optString("name", "未知歌曲"),
            artist = artist.ifEmpty { "未知歌手" },
            cover = secureUrl(raw.optString("pic", "")),
            stream = stream,
            lrc = secureUrl(raw.optString("lrc", "")),
            source = "meting",
        )
    }

    /** 从 `...?server=netease&type=url&id=210049` 里抠出歌曲 id。 */
    private fun idFromUrl(url: String): Long {
        val at = url.indexOf("id=")
        if (at < 0) return 0L
        val digits = url.substring(at + 3).takeWhile { it.isDigit() }
        return digits.toLongOrNull() ?: 0L
    }

    /** `hotAlbums[]` → 全项目统一的专辑形状。老接口叫 `size`，我们叫 trackCount。 */
    private fun normalizeAlbums(raw: Any?): List<Album> {
        val array = raw as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val e = array.optJSONObject(i) ?: return@mapNotNull null
            val name = e.optString("name", "").trim()
            if (name.isEmpty()) return@mapNotNull null
            Album(
                id = e.optLong("id", 0L),
                name = name,
                cover = secureUrl(e.optString("picUrl", "")),
                trackCount = e.optInt("size", 0),
                artist = asDict(e.opt("artist"))?.optString("name", "").orEmpty(),
            )
        }
    }

    private fun normalizePlaylists(raw: Any?): List<Playlist> {
        val array = raw as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val e = array.optJSONObject(i) ?: return@mapNotNull null
            Playlist(
                id = e.optLong("id", 0L),
                name = e.optString("name", "未命名歌单"),
                cover = secureUrl(e.optString("picUrl", e.optString("coverImgUrl", ""))),
                trackCount = e.optInt("trackCount", 0),
                playCount = e.optLong("playCount", 0L),
                creator = asDict(e.opt("creator"))?.optString("nickname", "").orEmpty(),
                description = e.optString("description", ""),
            )
        }
    }

    // -----------------------------------------------------------------------
    // 传输
    // -----------------------------------------------------------------------

    /** 拼 MeTing 请求：音源取自设置（默认网易云）；serverOverride 用来强制某个音源（歌词逐级回退）。 */
    private fun metingUrl(type: String, id: String, extra: String = "", serverOverride: String = ""): String {
        val server = serverOverride.ifEmpty { prefs.sourceServer() }
        return "$METING_URL?server=$server&type=$type&id=$id$extra"
    }

    /**
     * 网易云会下发几个 http 封面地址，同一批主机都支持 https。
     * 归一成 https 后，导出的 APK 不必申请 cleartext 豁免，封面也不会被静默清空。
     */
    private fun secureUrl(url: String): String =
        if (url.startsWith("http://")) "https://" + url.substring(7) else url

    /** 老接口回包：code 必须是 200 才算可用。 */
    private fun payload(data: Any?): JSONObject {
        val dict = asDict(data) ?: return JSONObject()
        if (dict.optInt("code", 0) != 200) return JSONObject()
        return dict
    }

    private fun asDict(value: Any?): JSONObject? = value as? JSONObject

    private suspend fun getJson(url: String): Any? = withContext(Dispatchers.IO) {
        val body = getText(url)
        if (body.isBlank()) return@withContext null
        try {
            val trimmed = body.trim()
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONObject(trimmed)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) PullUp/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }
}

class ApiException(message: String) : Exception(message)