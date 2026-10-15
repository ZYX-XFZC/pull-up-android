# Pull Up · Android

一款由 AI 辅助开发的**轻量化、无服务器、纯代码** Android 音乐播放器。（部分功能未实现，存在一些BUG）

> 原为 Godot 4.7 概念版（Pulse），现 100% 重构为原生 Android 工程：Kotlin + Jetpack Compose + Media3，零 XML 布局、零 Godot 依赖。APK 约 12.8 MB（R8 压缩 + v2 签名）。

## 功能特性

- 🎵 在线搜索 / 播放（网易云 + MeTing 混合源）
- 📃 歌词同步（原文 + 译文，居中沉浸式）
- 🔊 多档音质（标准 / 较高 / 极高，实测档位真源下发）
- 🖼 播放页背景取封面主色 + 模糊
- ❤️ 收藏 / 播放历史 / 歌单 / 私密歌单
- 🌓 深色 / 浅色主题（默认深色）
- 📱 系统媒体控制中心（MediaSession + 通知栏控制）
- 🎚 播放速度 / 循环模式 / 定时关闭
- 💾 数据全本地（DataStore），无服务器、无遥测

## 技术栈

| 项 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.1.21 |
| UI | Jetpack Compose（BOM 2025.06.00），Material3 动态主题 token |
| 播放 | Media3 / ExoPlayer 1.5.1 + ResolvingDataSource 伪协议解析 |
| 网络 | OkHttp |
| 图片 | Coil |
| 持久化 | DataStore（Preferences） |
| 构建 | AGP 8.9.3 / Gradle 8.11.1 / JDK 21 / compileSdk 36 / minSdk 29 |

## 构建

```bash
git clone https://github.com/ZYX-XFZC/pull-up-android.git
cd pull-up-android
./gradlew :app:assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

单元测试（JVM / Robolectric，65 用例）：

```bash
./gradlew :app:testDebugUnitTest
```

## 已知缺口

- 本地音乐导入待改走 SAF（Android 无原版 FileDialog）
- API调用逻辑问题，部分VIP音乐只能试听
- 搜索历史目前仅存内存，重启即丢
- 部分机型播放页打开异常待真机 logcat 定位
- 播放音乐时闪退，无播放页面（该问题较严重）

## 许可证

本项目采用 [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/)（署名 - 非商业性使用）许可证。

- ✅ 允许：学习、修改、分发（需署名）
- ❌ 禁止：商业用途

## 声明

- 本项目仅供学习交流，在线音源来自第三方接口，请勿用于商业用途。
- 本项目由 AI 辅助开发。
