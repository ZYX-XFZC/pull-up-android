package com.pullup.player.data

import android.content.Context

/**
 * 手写依赖容器。项目规模用不上 Hilt，显式构造反而更好读、构建更快。
 *
 * 全部单例，生命周期与应用一致；[PlaybackController] 之类需要 ExoPlayer 的组件
 * 在 Application.onCreate 里建一次，后台服务与界面共享同一个实例。
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val preferences = Preferences(appContext)
    val api = MusicApi(appContext, preferences)
    val favorites = Favorites(appContext)
    val history = History(appContext)
    val playlists = Playlists(appContext, favorites)
    val queuePlaylists = QueuePlaylists(appContext)
    val blacklist = Blacklist(appContext)
    val searchHistory = SearchHistory(appContext)
    val localLibrary = LocalLibrary(appContext)
    val customArt = CustomArt(appContext)

    val playback = com.pullup.player.player.PlaybackController(
        context = appContext,
        api = api,
        prefs = preferences,
        history = history,
    ).also { controller ->
        // 本地文件扫描时读不出时长（列表显示 0:00），播放器探测到真实时长后回灌给曲库。
        controller.onLocalDurationResolved = { fileName, seconds ->
            localLibrary.setDuration(fileName, seconds)
        }
    }
}
