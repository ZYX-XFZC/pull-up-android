package com.pullup.player.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.pullup.player.MainActivity
import com.pullup.player.PullUpApp
import com.pullup.player.R

/**
 * Media3 后台播放服务：常驻通知、锁屏控制、系统媒体控制中心、耳机按键都由框架提供。
 *
 * **播放器实例不由本服务创建** —— 它来自 [PullUpApp] 的容器，界面与服务共享同一个
 * ExoPlayer；本服务只负责把 MediaSession 挂到它上面（单进程应用的推荐做法）。
 * 因此 onDestroy 里**不能**释放播放器，那是容器的事。
 *
 * 原版对照：Godot 版另有一份 `android/build/src/main/java/com/godot/game/PlaybackService.java`
 * （313 行，`mediaPlayback` 前台服务 + 常驻通知，Manifest 里 `stopWithTask="false"`）。
 * 它保证了「退到后台/熄屏继续放」，但那份服务是个**裸 Service**，没有 MediaSession，
 * 所以通知栏里只有一条死通知，系统媒体控制中心（锁屏控件、蓝牙面板、Android 13+ 的
 * 媒体播放器卡片）里**看不到 Pull Up**。这里换用 MediaSessionService 把两者一次补齐。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val controller = (application as? PullUpApp)?.container?.playback ?: run {
            stopSelf()
            return
        }

        // 渠道名走自己的中文字符串资源，不用 media3 自带的那份（它未必是中文）。
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build(),
        )

        val session = MediaSession.Builder(this, controller.player)
            // 固定 id：Media3 用 id 在全局表里去重，id 为空会让「服务重启一次」撞上
            // `IllegalStateException: Session ID must be unique. ID=`。给个稳定 id 也让系统
            // 媒体控制中心能认出是同一个会话而不是每次新建一个。
            .setId(SESSION_ID)
            // 没有它，点通知/点媒体控制中心卡片不会打开 App（通知的内容意图直接取自这里）。
            .setSessionActivity(openAppIntent())
            .build()

        // 界面是直接操作 ExoPlayer 的，不建 MediaController，于是 onGetSession 永远不会被触发，
        // 会话也就永远不会被纳管、通知管理器不会 attach（⇒ 没有通知、没有控制中心条目）。
        // 单进程应用按官方说明显式登记一次即可。
        addSession(session)
        mediaSession = session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 与原版 stopWithTask="false" 同义：还有声音在放就一直活着，否则收摊。
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.let { session ->
            // 没登记过就直接 removeSession 会抛 IllegalArgumentException（框架内部有 checkArgument）。
            if (isSessionAdded(session)) removeSession(session)
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** 点通知 / 媒体控制中心卡片要落到的界面。 */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        /** 与原版 PlaybackService.java 的 `CHANNEL_ID = "pullup_playback"` 保持一致。 */
        private const val CHANNEL_ID = "pullup_playback"

        /**
         * 会话 id。**必须显式给**：Media3 的 `MediaSession.Builder` 不设 id 时用空串，而它内部
         * 有一张全局 `SESSION_ID_TO_SESSION_MAP`，遇到同 id 会抛
         * `IllegalStateException: Session ID must be unique. ID=`。服务被重启（或被测试反复构建）
         * 时旧会话若还没 release，空 id 就会互相撞上。给个稳定 id 顺带让系统认出是同一个会话。
         */
        private const val SESSION_ID = "pullup_playback_session"

        /**
         * 起服务。**必须用普通 startService**：MediaSessionService 在真正需要通知时
         * 由 Media3 自己调 `startForegroundService` 升前台，这里若抢先升前台又会因为
         * 5 秒内没 `startForeground` 而崩。
         *
         * 调用时机：Activity 处在前台（后台起服务会被系统拒绝）。播放本身不依赖它，
         * 失败也只是没有后台保活，所以这里吞掉异常。
         */
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
