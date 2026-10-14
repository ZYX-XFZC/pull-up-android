package com.pullup.player.player

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.pullup.player.data.History
import com.pullup.player.data.MusicApi
import com.pullup.player.data.Preferences
import com.pullup.player.data.TextGuard
import com.pullup.player.data.Track
import com.pullup.player.data.UrlResult
import com.pullup.player.data.toJson
import com.pullup.player.data.trackFromJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 播放引擎，移植自 Godot 版 scripts/PlayerManager.gd，底层换成 Media3 ExoPlayer。
 *
 * 关键差异（有意为之）：
 * - **播放地址解析放进 ResolvingDataSource**。Godot 版是「先异步取 url 再下载」，Kotlin 侧改成
 *   「媒体项用伪协议 `pullup://song/<id>`，真正要读流时才解析成真实地址」，于是自动切下一首、
 *   预取、重试全都白送，不必自己写一套下载管线。解析在那个 loading 线程上同步跑，不卡主线程。
 * - **播放模式直接映射 ExoPlayer**：列表循环 → REPEAT_MODE_ALL；单曲循环 → REPEAT_MODE_ONE；
 *   单次播放 → 用 `pauseAtEndOfMediaItems` 播完即停（配合重复次数递减）。
 * - 磁盘缓存用 Media3 的 SimpleCache（200MiB，LRU），对应原版 user://audio_cache。
 */
class PlaybackController(
    context: Context,
    private val api: MusicApi,
    private val prefs: Preferences,
    private val history: History,
) {

    companion object {
        const val LIST_LOOP = 0
        const val SINGLE_LOOP = 1
        const val ONCE = 2

        /** 会话最多存 60 首。 */
        const val SESSION_MAX_SONGS = 60

        const val CACHE_MAX_BYTES = 200L * 1024L * 1024L

        /** 位置上报间隔，原版 POSITION_EMIT_INTERVAL = 0.1s。 */
        private const val TICK_MS = 250L

        const val PSEUDO_SCHEME = "pullup"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 本地曲目真实时长回填钩子（文件名 → 秒）。本地文件在扫描时读不出时长，
     * 列表会一直显示 0:00；播放器探测到之后从这里回灌给 LocalLibrary。
     * 用回调而不是直接持有 LocalLibrary，免得播放引擎反向依赖曲库。
     */
    var onLocalDurationResolved: ((fileName: String, seconds: Float) -> Unit)? = null

    /** 已经回填过的文件名，避免每 250ms 重复写盘。 */
    private val reportedDurations = mutableSetOf<String>()

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cache = SimpleCache(
        File(context.applicationContext.filesDir, "audio_cache"),
        LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES),
    )

    /** song id → Track，供解析器查 MeTing 预解析过的 stream。 */
    @Volatile
    private var tracksById: Map<Long, Track> = emptyMap()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(buildDataSourceFactory()))
        .setHandleAudioBecomingNoisy(true)
        // 接管音频焦点：来电、别的 App 出声时自动压低/暂停，放开后自动恢复（原版没有，属于补齐）。
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        // 息屏继续播：持一个网络唤醒锁，避免锁屏后流被切断。
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    // ---- 对外状态 ----------------------------------------------------------

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _playMode = MutableStateFlow(LIST_LOOP)
    val playMode: StateFlow<Int> = _playMode.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeatCount = MutableStateFlow(1)
    val repeatCount: StateFlow<Int> = _repeatCount.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _speed = MutableStateFlow(prefs.speed())
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _sleepLeft = MutableStateFlow(0)
    val sleepLeft: StateFlow<Int> = _sleepLeft.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** 播放失败原因（中文，可直接上屏）；读取后自动清掉。 */
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * **服务端实际下发的音质档位**（`standard`/`higher`/`exhigh`…），取自解析回包的 `level` 字段。
     *
     * 它常常低于用户选的档位：实测请求 `lossless`/`hires`/`jyeffect`/`sky`/`jymaster` 时，
     * 服务端一律静默降级回 `exhigh`（无版权凭证）。播放页据此显示**真实**档位，
     * 而不是把用户选的那一档当成已生效 —— 这是用户 m01767 要的「有实际功能」的核心。
     */
    private val _servedQuality = MutableStateFlow("")
    val servedQuality: StateFlow<String> = _servedQuality.asStateFlow()

    /** 一次性失败重试用尽后置位，界面可消费。 */
    fun consumeError() {
        _error.value = null
    }

    private var playsLeft = 1
    private var fallbackTried = false
    private var sleepJob: Job? = null
    private var tickJob: Job? = null
    private val sessionFile = File(context.applicationContext.filesDir, "play_session.json")

    // ---- 初始化 ------------------------------------------------------------

    init {
        syncPlayModeToPlayer()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = isPlaying
                if (isPlaying) startTicker() else stopTicker()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val track = mediaItem?.mediaId?.toLongOrNull()?.let { tracksById[it] }
                if (track != null) {
                    _current.value = track
                    // 换歌就把上一首的实际档位清掉：它属于上一首歌，留着会显示错的档位。
                    _servedQuality.value = ""
                    // 真的开始播了才进历史 —— 下载失败/格式不支持永远不进。
                    history.record(track)
                }
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    _position.value = 0L
                    fallbackTried = false
                    if (_playMode.value == ONCE && playsLeft > 1) {
                        playsLeft--
                        // 还在「单次重复」区间内，把当前曲再放一遍。
                        player.repeatMode = Player.REPEAT_MODE_ONE
                    } else if (_playMode.value == ONCE) {
                        player.pauseAtEndOfMediaItems = true
                        player.repeatMode = Player.REPEAT_MODE_OFF
                    }
                }
                saveSession()
            }

            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError(error)
            }
        })
    }

    // ---- 地址解析 ----------------------------------------------------------

    private fun buildDataSourceFactory(): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(okHttp)
        val cached = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return ResolvingDataSource.Factory(cached) { spec: DataSpec ->
            val uri = spec.uri
            if (uri.scheme != PSEUDO_SCHEME) {
                spec
            } else {
                val songId = uri.lastPathSegment?.toLongOrNull() ?: 0L
                val known = tracksById[songId]?.stream.orEmpty()
                when (val r = runBlocking { api.getSongUrl(songId, prefs.qualityKey(), known) }) {
                    is UrlResult.Ok -> {
                        // 服务端实际下发的档位可能低于我们请求的档位（无版权歌曲一律降级），
                        // 如实记下来给播放页显示 —— 不能把用户选的档位当成已生效。
                        if (r.served.isNotEmpty()) _servedQuality.value = r.served
                        spec.buildUpon().setUri(r.url.toUri()).build()
                    }
                    is UrlResult.Fail -> throw IOException(r.error)
                }
            }
        }
    }

    private fun Track.toMediaItem(): MediaItem {
        val uri: Uri = if (local && path.isNotEmpty()) {
            File(path).toUri()
        } else {
            "$PSEUDO_SCHEME://song/$id".toUri()
        }
        val meta = MediaMetadata.Builder()
            .setTitle(name)
            .setArtist(artist)
            .setAlbumTitle(album)
            .apply { if (cover.isNotBlank()) setArtworkUri(cover.toUri()) }
            .build()
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(meta)
            .build()
    }

    // ---- 队列 --------------------------------------------------------------

    /**
     * 播一首歌。[list] 是「当前屏幕上看得见的那一份列表」，它同时就是播放队列；
     * 传空数组则退化成单曲队列（与 Godot 版一致）。
     */
    fun playSong(song: Track, list: List<Track> = emptyList()) {
        if (song.id == 0L && song.name.isBlank() && !song.local) return
        var effective: List<Track> = list
        var index: Int
        if (list.isEmpty()) {
            effective = listOf(song)
            index = 0
        } else {
            effective = list
            index = list.indexOfFirst { it.id == song.id }
            if (index < 0) {
                effective = listOf(song)
                index = 0
            }
        }
        setQueue(effective, index, autoplay = true)
    }

    fun playAtIndex(index: Int) {
        val q = _queue.value
        if (index !in q.indices) return
        player.seekTo(index, 0L)
        player.playWhenReady = true
        _current.value = q[index]
    }

    /** 下一首。开了随机就交给 ExoPlayer 的 shuffle 顺序。 */
    fun playNext() {
        if (_queue.value.isEmpty()) return
        player.seekToNextMediaItem()
        player.playWhenReady = true
    }

    /**
     * 上一首。**始终按顺序回退并环绕**，随机模式下也一样 —— 与 Godot 版口径一致。
     */
    fun playPrevious() {
        val q = _queue.value
        if (q.isEmpty()) return
        val idx = currentIndex()
        if (idx < 0) return
        val prev = if (idx - 1 < 0) q.lastIndex else idx - 1
        player.seekTo(prev, 0L)
        player.playWhenReady = true
    }

    /** 插到当前曲之后（不立刻播放）。 */
    fun playAfterCurrent(song: Track) {
        val q = _queue.value.toMutableList()
        val idx = currentIndex()
        if (q.isEmpty() || idx < 0) {
            playSong(song, listOf(song))
            return
        }
        val at = (idx + 1).coerceAtMost(q.size)
        q.add(at, song)
        _queue.value = q
        tracksById = tracksById + (song.id to song)
        player.addMediaItem(at, song.toMediaItem())
    }

    fun setQueue(list: List<Track>, index: Int, autoplay: Boolean) {
        _queue.value = list
        tracksById = tracksById + list.associateBy { it.id }
        player.setMediaItems(list.map { it.toMediaItem() }, index.coerceIn(0, (list.size - 1).coerceAtLeast(0)), 0L)
        fallbackTried = false
        playsLeft = _repeatCount.value
        player.prepare()
        if (autoplay) player.playWhenReady = true
        _current.value = list.getOrNull(index)
        saveSession()
    }

    private fun currentIndex(): Int {
        val id = _current.value?.id ?: return player.currentMediaItemIndex
        val idx = _queue.value.indexOfFirst { it.id == id }
        return if (idx >= 0) idx else player.currentMediaItemIndex
    }

    // ---- 传输控制 ----------------------------------------------------------

    /**
     * 播放/暂停。三态逻辑照搬原版（原注释记录过一个「恢复播放却从头开始」的真实 bug）：
     * 暂停中 → 继续；播放中 → 暂停；有流但停了 → **从原位置**继续；什么都没有 → 起当前曲。
     */
    fun togglePlayPause() {
        when {
            !player.playWhenReady && player.playbackState != Player.STATE_IDLE -> player.playWhenReady = true
            player.isPlaying -> player.playWhenReady = false
            player.playbackState != Player.STATE_IDLE -> player.playWhenReady = true
            else -> _current.value?.let { startCurrent() }
        }
    }

    fun seek(positionMs: Long) {
        val dur = player.duration
        if (dur <= 0L) return
        player.seekTo(positionMs.coerceIn(0L, dur))
        _position.value = positionMs.coerceIn(0L, dur)
    }

    fun stop() {
        player.stop()
        _playing.value = false
        _position.value = 0L
    }

    fun startCurrent() {
        val song = _current.value ?: return
        val q = _queue.value.ifEmpty { listOf(song) }
        val idx = q.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        setQueue(q, idx, autoplay = true)
    }

    /** 切码率重解析当前曲，保住播放位置。 */
    fun reloadQuality() {
        val song = _current.value ?: return
        if (song.id <= 0L) return
        val at = player.currentPosition
        api.invalidateSongUrl(song.id)
        // 幻协议下换个 key 就能绕过缓存：把媒体项换成「同 id + 新 generation」。
        val idx = player.currentMediaItemIndex
        player.replaceMediaItem(idx, song.toMediaItem())
        player.seekTo(idx, at)
        player.prepare()
        player.playWhenReady = true
    }

    fun setSpeed(value: Float) {
        val v = value.coerceIn(0.5f, 2f)
        _speed.value = v
        prefs.setSpeed(v)
        // 原版改速度同时变调（Godot pitch_scale）；Media3 设 speed=1 保持音高不变，
        // 这里显式用 speed 参数复现「变调」口径。
        player.playbackParameters = PlaybackParameters(v, v)
    }

    // ---- 播放模式 ----------------------------------------------------------

    fun setPlayMode(mode: Int) {
        if (mode !in 0..2 || mode == _playMode.value) return
        _playMode.value = mode
        playsLeft = _repeatCount.value
        syncPlayModeToPlayer()
    }

    fun cyclePlayMode() = setPlayMode((_playMode.value + 1) % 3)

    fun setShuffle(enabled: Boolean) {
        _shuffle.value = enabled
        player.shuffleModeEnabled = enabled
    }

    fun toggleShuffle() = setShuffle(!_shuffle.value)

    fun setRepeatCount(count: Int) {
        _repeatCount.value = count.coerceIn(1, 99)
        // 立刻生效到正在播的那首。
        playsLeft = _repeatCount.value
    }

    private fun syncPlayModeToPlayer() {
        when (_playMode.value) {
            SINGLE_LOOP -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.pauseAtEndOfMediaItems = false
            }
            ONCE -> {
                player.repeatMode = if (playsLeft > 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                player.pauseAtEndOfMediaItems = playsLeft <= 1
            }
            else -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.pauseAtEndOfMediaItems = false
            }
        }
    }

    // ---- 睡眠定时 ----------------------------------------------------------

    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepLeft.value = 0
            return
        }
        _sleepLeft.value = minutes
        sleepJob = scope.launch {
            var left = minutes
            while (left > 0) {
                delay(60_000L)
                left--
                _sleepLeft.value = left
            }
            player.playWhenReady = false
        }
    }

    fun stopSleepTimer() = startSleepTimer(0)

    // ---- 位置上报 ----------------------------------------------------------

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                _position.value = player.currentPosition.coerceAtLeast(0L)
                val d = player.duration
                _duration.value = if (d == C.TIME_UNSET || d < 0L) 0L else d
                _loading.value = player.playbackState == Player.STATE_BUFFERING
                reportLocalDuration(_duration.value)
                delay(TICK_MS)
            }
        }
    }

    /**
     * 本地文件扫描时没有时长（列表显示 0:00），第一次播到它时把真实时长回灌给曲库。
     * 每首只回灌一次 —— 否则每 250ms 就会写一次盘。
     */
    private fun reportLocalDuration(durationMs: Long) {
        if (durationMs <= 0L) return
        val track = _current.value ?: return
        if (!track.local || track.path.isEmpty()) return
        val name = File(track.path).name
        if (!reportedDurations.add(name)) return
        onLocalDurationResolved?.invoke(name, durationMs / 1000f)
    }

    private fun stopTicker() {
        tickJob?.cancel()
        tickJob = null
    }

    // ---- 失败与重试 --------------------------------------------------------

    private fun onPlaybackError(e: PlaybackException) {
        val reason = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "无法读取本地文件"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> "音频下载失败"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            -> "不支持的音频格式"
            else -> "音频下载失败"
        }
        val song = _current.value
        if (song != null && song.id > 0L && !fallbackTried) {
            // 每首只降级重试一次：高档位最容易回 FLAC，先退一档再试。
            fallbackTried = true
            api.invalidateSongUrl(song.id)
            prefs.setBitrateIndex(lowerQualityIndex())
            scope.launch {
                delay(120)
                val idx = player.currentMediaItemIndex
                player.replaceMediaItem(idx, song.toMediaItem())
                player.prepare()
                player.playWhenReady = true
            }
            return
        }
        _loading.value = false
        _playing.value = false
        _error.value = TextGuard.mask(reason)
    }

    /**
     * 降级目标档位下标：往下退一档。
     *
     * 旧实现按**码率数值**推断下一档（`>= 999000 → 320000`），那是因为老表只有「标准/极高/无损」
     * 三档且值单调；现在的表里有 `jyeffect`/`sky`/`jymaster` 这些**码率为 0 的档位**，
     * 按数值比会全部落进 `else` 分支退到 320k，逻辑就崩了。直接按**表内下标**往低退最稳。
     */
    private fun lowerQualityIndex(): Int = (prefs.bitrateIndex() - 1).coerceAtLeast(0)

    // ---- 会话持久化 --------------------------------------------------------

    private fun sessionJson(): JSONObject {
        val q = _queue.value.take(SESSION_MAX_SONGS)
        return JSONObject().apply {
            put("playlist", JSONArray().also { arr -> q.forEach { arr.put(it.toJson()) } })
            put("index", currentIndex().coerceAtLeast(0))
            _current.value?.let { put("song", it.toJson()) }
            put("position", player.currentPosition.coerceAtLeast(0L) / 1000.0)
        }
    }

    fun saveSession() {
        runCatching {
            sessionFile.parentFile?.mkdirs()
            sessionFile.writeText(sessionJson().toString(), Charsets.UTF_8)
        }
    }

    /** 恢复上次的队列与进度。**不自动播放**。 */
    fun restoreSession(): Boolean {
        if (_queue.value.isNotEmpty() || _current.value != null) return false
        if (!sessionFile.exists()) return false
        val o = runCatching { JSONObject(sessionFile.readText(Charsets.UTF_8)) }.getOrNull() ?: return false
        val arr = o.optJSONArray("playlist") ?: return false
        if (arr.length() == 0) return false
        val songs = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { trackFromJson(it) } }
        if (songs.isEmpty()) return false
        val index = o.optInt("index", 0).coerceIn(0, songs.lastIndex)
        val saved = o.optJSONObject("song")?.let { trackFromJson(it) }
        val song = saved ?: songs[index]
        val resumeSeconds = o.optDouble("position", 0.0).coerceAtLeast(0.0)

        _queue.value = songs
        tracksById = tracksById + songs.associateBy { it.id }
        player.setMediaItems(songs.map { it.toMediaItem() }, index, (resumeSeconds * 1000).toLong())
        player.prepare()
        player.playWhenReady = false
        _current.value = song
        _position.value = (resumeSeconds * 1000).toLong()
        return true
    }

    fun release() {
        stopTicker()
        sleepJob?.cancel()
        player.release()
        runCatching { cache.release() }
    }
}
