plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.whmdg.mczj.tools"
    ndkVersion = "27.0.12077973"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.whmdg.mczj.tools"
        minSdk = 24
        targetSdk = 36
        val gitCommitCount = try {
            val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            proc.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 0
        } catch (_: Exception) { 0 }
        versionCode = 1400000000 + gitCommitCount
        val ts = System.currentTimeMillis() / 1000
        versionName = "2.4.%d".format(ts)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("app/release-keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            // isMinifyEnabled = true      // 暂时禁用 R8 压缩，太慢
            // isShrinkResources = true    // 暂时禁用资源压缩
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val envOk = System.getenv("KEYSTORE_PASSWORD")?.isNotEmpty() == true &&
                        System.getenv("KEY_ALIAS")?.isNotEmpty() == true
            signingConfig = if (envOk) signingConfigs.getByName("release")
                           else signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)

    implementation(libs.bouncycastle.bcprov)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.webkit)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation(platform(libs.sora.editor.bom))
    implementation(libs.sora.editor)
    implementation(libs.sora.language.java)
    implementation(libs.photoView)
    implementation(libs.coil.compose)
    implementation(libs.libsu.core)
    implementation(libs.androidx.appcompat)

    // 压缩/解压
    implementation("net.lingala.zip4j:zip4j:2.11.5")          // ZIP + AES 加密
    implementation("org.apache.commons:commons-compress:1.26.1") // TAR/GZ/BZ2/XZ/7z
    implementation("com.github.junrar:junrar:7.5.5")           // RAR 解压
    implementation("org.tukaani:xz:1.9")                       // XZ 压缩（commons-compress 依赖）
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}