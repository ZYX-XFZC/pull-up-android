package com.pullup.player

import android.app.Application
import com.pullup.player.data.AppContainer

/**
 * 应用入口：只持有一个手写容器（够用且零反射，不引 Hilt）。
 */
class PullUpApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
