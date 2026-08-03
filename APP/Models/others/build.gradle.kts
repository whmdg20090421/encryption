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
    implementation(libs.telephoto.zoomable)
    implementation(libs.jxl.coder)
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
    compileOnly("de.robv.android.xposed:api:80")
    // YukiHookAPI
    implementation("com.highcapable.yukihookapi:api:${libs.versions.yukihookapi.get()}")
    ksp("com.highcapable.yukihookapi:ksp-xposed:${libs.versions.yukihookapi.get()}")
}
