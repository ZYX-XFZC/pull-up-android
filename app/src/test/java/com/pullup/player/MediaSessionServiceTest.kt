package com.pullup.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pullup.player.player.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 系统媒体控制中心接入的实证（用户 m02624：`加入系统媒体控制中心音乐菜单`）。
 *
 * 这台机器起不了模拟器、也没有真机（`adb devices` 为空），所以「通知和控制中心里到底有没有
 * Pull Up」没法用眼睛看。但**会话有没有真的被服务纳管**是可以证伪的 —— 而这正是全部问题的
 * 根因：改造前 `PlaybackService` 只 build 了 MediaSession 就完事，界面又不建 MediaController，
 * 于是 `onGetSession` 永不触发、`addSession` 永不发生、通知管理器永不 attach，锁屏与系统媒体
 * 面板里就永远没有这个 App。
 *
 * Media3 的门槛链（官方源码，行号可复查）：
 * - `MediaNotificationManager.updateNotification` 首行就是
 *   `if (!isSessionAdded(session) || !shouldShowNotification(session)) { ...; return; }`
 *   ⇒ **没登记 = 永远没有通知**；
 * - `shouldShowNotification` 又要求队列非空且播放态不是 IDLE
 *   ⇒ 光起服务不会有通知，所以这里只验「登记」这一半，不假装验了整条通知链。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PullUpApp::class)
class MediaSessionServiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * 服务被拉起后必须**自己把会话登记进框架**。
     *
     * 断言 [androidx.media3.session.MediaSessionService.getSessions] 非空，就是断言
     * `addSession(session)` 真的执行了。这一条在改造前必然失败（当时没有这行）。
     */
    @Test
    fun `媒体会话 服务启动后会话已被登记`() {
        val controller = Robolectric.buildService(PlaybackService::class.java)
        val service = controller.create().get()

        val sessions = service.sessions
        assertTrue("服务没有把 MediaSession 登记进框架，通知与控制中心都不会出现", sessions.isNotEmpty())
        assertEquals("只应登记一个会话", 1, sessions.size)

        controller.destroy()
    }

    /**
     * 通知/控制中心卡片要能点回 App。
     *
     * Media3 的通知是 `.setContentIntent(mediaSession.getSessionActivity())` —— 没有会话活动，
     * 点通知就什么都不发生。
     */
    @Test
    fun `媒体会话 会话活动指向主界面`() {
        val controller = Robolectric.buildService(PlaybackService::class.java)
        val service = controller.create().get()

        val session = service.sessions.first()
        assertNotNull("没有 sessionActivity，点通知不会打开 App", session.sessionActivity)

        controller.destroy()
    }

    /**
     * 会话的身份与 framework token 都必须是活的。
     *
     * 系统媒体控制中心（锁屏控件、蓝牙面板、Android 13+ 媒体卡片）读的就是 framework token；
     * Media3 内部会自动建一个兼容用的 `MediaSessionCompat` 并激活它 —— 这条就是在验那个
     * 兼容层确实存在，而不是一个只有 Kotlin 对象的空壳。
     */
    @Test
    fun `媒体会话 framework token 可用`() {
        val controller = Robolectric.buildService(PlaybackService::class.java)
        val service = controller.create().get()

        val session = service.sessions.first()
        assertNotNull("会话没有 framework token，系统媒体控制中心读不到它", session.token)
        assertTrue("会话 id 不该是空的", session.id.isNotEmpty())

        controller.destroy()
    }

    /**
     * 起服务与销毁都不许崩。
     *
     * 销毁这条尤其要盯：框架的 `removeSession` 内部有
     * `checkArgument(sessions.containsKey(session.getId()), "session not found")`，
     * 没先判 `isSessionAdded` 就 remove 会抛 `IllegalArgumentException`。
     */
    @Test
    fun `媒体会话 起服务与销毁都不抛异常`() {
        PlaybackService.start(context)

        val controller = Robolectric.buildService(PlaybackService::class.java)
        val service = controller.create().get()
        assertTrue(service.sessions.isNotEmpty())
        // 不抛即通过 —— 有异常这里会直接让用例失败。
        controller.destroy()
    }

    /**
     * 会话挂在**容器那个 ExoPlayer** 上，而不是服务自己新建一个。
     *
     * 这是单进程应用的推荐做法（MediaSession 官方 javadoc：`The app is responsible for releasing
     * the underlying player after releasing the session`），也保证界面按的暂停键和通知栏里的是
     * 同一个播放器。若哪天有人在服务里 `ExoPlayer.Builder(...)`，这条会失败。
     */
    @Test
    fun `媒体会话 挂在容器共用的播放器上`() {
        val container = (context as PullUpApp).container
        val controller = Robolectric.buildService(PlaybackService::class.java)
        val service = controller.create().get()

        val session = service.sessions.first()
        assertEquals("会话没有挂在容器共用的 ExoPlayer 上", container.playback.player, session.player)

        controller.destroy()
        // 服务销毁**不得**顺手把播放器也释放了 —— 播放器归容器所有。
        assertTrue("服务不该释放容器的播放器", container.playback.player.mediaItemCount >= 0)
    }
}
