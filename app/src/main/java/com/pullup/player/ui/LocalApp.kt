package com.pullup.player.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.pullup.player.data.AppContainer

/**
 * 数据容器下发给整棵界面树。等价于 Godot 版的全局 autoload 单例
 * （`Favorites` / `Playlists` / `PlayerManager` … 任何脚本直接按名字取）。
 */
val LocalApp = staticCompositionLocalOf<AppContainer> { error("AppContainer 未提供") }
