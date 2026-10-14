package com.pullup.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.pullup.player.player.PlaybackService
import com.pullup.player.ui.LocalApp
import com.pullup.player.ui.PullUpRoot
import com.pullup.player.ui.theme.PullUpTheme
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 唯一 Activity。等价于 Godot 版的 `scenes/main.tscn` + `scripts/main.gd`。
 *
 * 窗口保持竖屏（Manifest 里锁死），edge-to-edge 让内容铺到状态栏/导航栏底下，
 * 背景色由 themes.xml 的 windowBackground 给，避免冷启动先闪一帧白。
 */
class MainActivity : ComponentActivity() {

    /**
     * 通知权限只在 Android 13+ 存在。媒体会话通知本身被系统豁免（Media3 源码里写明了），
     * 但各家 ROM 不一定认这条豁免，所以还是照常申请一次 —— 用户拒了也不影响播放。
     */
    private val askNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 起后台播放服务。**必须趁 Activity 在前台时起**（后台起服务会被系统拒绝）；
        // 服务本身只是把 MediaSession 登记进框架，通知要等真有歌在放时才出现。
        PlaybackService.start(this)
        requestNotificationPermissionIfNeeded()

        val container = (application as PullUpApp).container
        setContent {
            PullUpTheme(container.preferences) {
                CompositionLocalProvider(LocalApp provides container) {
                    PullUpRoot(container)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 退到后台就存一次会话（对应原版 APPLICATION_PAUSED / WINDOW_FOCUS_OUT 的落盘时机）。
        (application as PullUpApp).container.playback.saveSession()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) askNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
