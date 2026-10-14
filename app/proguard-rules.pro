# Pull Up — R8 / resource-shrinking keep rules.
#
# 为什么需要这份文件：release 现在开了 isMinifyEnabled + isShrinkResources（用户 m02617 批准）。
# 本工程没有真机可回归（HypervisorPresent=False、adb devices 为空），所以规则取「保守够用」：
# 只保留**框架会反射构造**的入口，其余交给 AGP 默认规则 + 各 AAR 自带的 consumer rules。

# ---------------------------------------------------------------------------
# 1. 本 App 的组件入口
# ---------------------------------------------------------------------------
# Manifest 里声明的组件 AGP 默认会 keep，这里显式写一遍是因为 PlaybackService 同时被
# Media3 用「按类名启动」的方式拉起（MediaNotificationManager 内部 startForegroundService
# 到 mediaSessionService.getClass()），显式保留更稳。
-keep class com.pullup.player.PullUpApp { *; }
-keep class com.pullup.player.MainActivity { *; }
-keep class com.pullup.player.player.PlaybackService { *; }

# Application/Activity/Service 子类的构造器与生命周期方法不能被改名或删掉。
-keepclassmembers class * extends android.app.Application {
    public <init>(...);
}
-keepclassmembers class * extends android.app.Activity {
    public <init>(...);
}
-keepclassmembers class * extends android.app.Service {
    public <init>(...);
    public int onStartCommand(android.content.Intent, int, int);
    public android.os.IBinder onBind(android.content.Intent);
    public void onCreate();
    public void onDestroy();
    public void onTaskRemoved(android.content.Intent);
}

# ---------------------------------------------------------------------------
# 2. Media3 session / 通知
# ---------------------------------------------------------------------------
# media3-session 这个 AAR **没有自带 proguard.txt**（已实测：aar 里 258 个 entry，无任何
# proguard/consumer-rules 文件；exoplayer/common/datasource/ui 四个模块都有）。而会话的
# 服务端 stub、legacy 兼容层、通知 provider 都是框架按接口名反射接进来的，所以这里补上。
-keep class androidx.media3.session.** { *; }
-keep interface androidx.media3.session.** { *; }
-dontwarn androidx.media3.session.**

# 通知里用 Bundle 传的 framework MediaSession token（锁屏/系统媒体控制中心读它）。
-keep class android.support.v4.media.session.** { *; }
-keep class androidx.media3.session.legacy.** { *; }
-dontwarn android.support.v4.media.session.**

# ---------------------------------------------------------------------------
# 3. 数据层：org.json + 反射式反序列化
# ---------------------------------------------------------------------------
# 数据层全部走 org.json 手工读写（没有 kotlinx.serialization / Gson），字段名是字符串字面量，
# 所以不需要 keep 数据类。这里只保留 org.json 自己。
-keep class org.json.** { *; }
-dontwarn org.json.**

# ---------------------------------------------------------------------------
# 4. OkHttp / Okio
# ---------------------------------------------------------------------------
# 这两个库自带 consumer rules，但 OkHttp 会按平台探测可选依赖（Conscrypt、BouncyCastle、
# 以及 Android 上不存在的 JVM 实现），不开 dontwarn 会刷一堆 missing-class 警告。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# ---------------------------------------------------------------------------
# 5. Kotlin 元数据 / 协程
# ---------------------------------------------------------------------------
# 协程的 CoroutineExceptionHandler 等靠 ServiceLoader 找实现，保留 META-INF 服务声明。
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---------------------------------------------------------------------------
# 6. 资源收缩：保住媒体通知的小图标与渠道名
# ---------------------------------------------------------------------------
# DefaultMediaNotificationProvider 默认用 R.drawable.media3_notification_small_icon，
# 渠道名走本工程的 R.string.playback_channel_name。资源收缩靠静态引用判定，这里是双保险。
-keep class androidx.media3.session.DefaultMediaNotificationProvider { *; }
