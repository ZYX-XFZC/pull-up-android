import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名信息放 keystore/keystore.properties（自用项目，随仓库走）。
// 文件缺失时 release 仍然能编，只是产出未签名包 —— 不因为签名把整个构建卡死。
// 注意：这里必须走 import，写 `java.util.Properties()` 会被 Gradle Kotlin DSL 里的
// `java` 扩展（JavaPluginExtension）拦截，报 Unresolved reference: util。
val keystoreProps = Properties()
val keystoreFile = rootProject.file("keystore/keystore.properties")
if (keystoreFile.exists()) {
    keystoreFile.inputStream().use { stream -> keystoreProps.load(stream) }
}

android {
    namespace = "com.pullup.player"
    compileSdk = 36

    defaultConfig {
        // 测试版专用包名：与 Godot 先行测试版（同为 com.pullup.player）分开，
        // 两者可同机共存。注意 namespace 保持 com.pullup.player 不动 —— 清单里的
        // .PullUpApp / .MainActivity 这类相对类名是按 namespace 解析的，跟着改会全部找不到类。
        applicationId = "com.pullup.player.test"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 + 资源收缩（用户 m02617 明确批准：「开 R8（isMinifyEnabled + isShrinkResources），
            // 装机验证交给你」）。体积根因曾是 material-icons-extended（已整个移除，图标改走
            // 原版 PNG 图标集），现在 R8 仍负责剔掉 Compose/Media3 里没走到的分支。
            // keep 规则在 proguard-rules.pro，其中 media3-session 必须手工补（该 AAR 不自带规则）。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric 要读合并后的清单与资源，否则在 JVM 上拿不到主题/字体/字符串
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // material-icons-extended 已移除：界面图标全部走原版 PNG 图标集
    // （res/drawable-nodpi/*.png + ui/components/IconSet.kt 的 IconAsset），
    // 不再引用任何 Icons.Rounded.*。留着它只会往 R8 输入里灌几千个用不到的图标。
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Media3: 播放引擎 + 后台保活前台服务 + 锁屏/通知栏媒体控制。
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- 测试 --------------------------------------------------------------
    // 本机没有硬件加速（HypervisorPresent=False，且非管理员装不了 HAXM/WHPX），
    // x86_64 系统镜像起不来、也没有真机接入 —— 所以「跑起来看一眼」这条路由
    // Robolectric 在 JVM 上承担：它按真实的 Android 框架代码跑 Activity/Compose，
    // 只是没有真实 GPU 与模拟器。这样合入的每个改动都能拿到可复核的运行证据。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
