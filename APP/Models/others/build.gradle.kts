plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.whmdg.mczj.tools.others"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":APP:core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.video)
    implementation(libs.zoomimage.compose.coil3)
    implementation(libs.jxl.coder)
    // XLSX 生成（使用时长数据导出，fastexcel 仅 131KB）
    implementation("org.dhatim:fastexcel:0.20.2")
    implementation(platform(libs.sora.editor.bom))
    implementation(libs.sora.editor)
    implementation(libs.sora.language.java)
    implementation(libs.libsu.core)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // WebDAV
    implementation("com.github.bitfireAT:dav4jvm:02fe1a95e6b86e323bec3784d7d2fe2d4081dde6") {
        exclude(group = "org.ogce", module = "xpp3")
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // NanoHTTPD
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // libxposed API 102 (本地源码模块)
    compileOnly(project(":libs:libxposed-api"))
    // 旧版 Xposed API (KSP 生成代码引用 de.robv.android.xposed.*)
    compileOnly(project(":libs:xposed-api"))
    // YukiHookAPI
    implementation("com.highcapable.yukihookapi:api:${libs.versions.yukihookapi.get()}")
    ksp("com.highcapable.yukihookapi:ksp-xposed:${libs.versions.yukihookapi.get()}")

    // ========== GSYVideoPlayer（视频播放器） ==========
    // 当前：仅引入核心，后续按需开启扩展功能
    // 完整模块列表见下方注释，取消注释即可启用
    val gsyVersion = "13.2.1"

    // --- 核心（已启用） ---
    implementation("io.github.carguo:gsyvideoplayer-java:$gsyVersion")       // 核心 Java 类（播放器逻辑/UI/缩略图）
    implementation("io.github.carguo:gsyvideoplayer-exo2:$gsyVersion")       // Media3/ExoPlayer 播放引擎
    implementation("io.github.carguo:gsyvideoplayer-compose:$gsyVersion")    // Jetpack Compose 集成（Wrapper + Native）

    // --- 可选：IJK 引擎（与 exo2 二选一，用于格式兼容性更好的场景） ---
    // implementation("io.github.carguo:gsyvideoplayer-arm64:$gsyVersion")    // IJK 引擎 arm64-v8a native so
    // implementation("io.github.carguo:gsyvideoplayer-armv7a:$gsyVersion")  // IJK 引擎 armeabi-v7a native so
    // implementation("io.github.carguo:gsyvideoplayer-armv5:$gsyVersion")   // IJK 引擎 armeabi native so（极老设备）
    // implementation("io.github.carguo:gsyvideoplayer-x86:$gsyVersion")     // IJK 引擎 x86 native so（模拟器）
    // implementation("io.github.carguo:gsyvideoplayer-x64:$gsyVersion")     // IJK 引擎 x86_64 native so（模拟器）
    // implementation("io.github.carguo:gsyvideoplayer-ex_so:$gsyVersion")   // 扩展 IJK so（mpeg/rtsp/concat/crypto/16K页/更多编码）

    // --- 可选：流媒体协议 ---
    // implementation("io.github.carguo:gsyvideoplayer-rtmp:$gsyVersion")    // RTMP 直播推流（注：exo2 已内置 RTMP 支持）

    // --- 可选：其他播放引擎 ---
    // implementation("io.github.carguo:gsyvideoplayer-aliplay:$gsyVersion") // 阿里云播放器引擎（AliPlayer）

    // --- 可选：投屏 ---
    // implementation("io.github.carguo:gsyvideoplayer-cast:$gsyVersion")    // DLNA/UPnP 电视投屏（minSdk 26）

    // --- 可选：序列化 ---
    // implementation("io.github.carguo:gsyvideoplayer-gson:$gsyVersion")    // Gson 序列化支持（播放状态保存/恢复）
}
