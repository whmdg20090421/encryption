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
        versionName = "2.8.%d".format(ts)

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

    packaging {
        jniLibs {
            useLegacyPackaging = true  // 不压缩 so 文件，确保二进制可直接执行
        }
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
    implementation(libs.telephoto.zoomable)
    implementation(libs.coil.compose)
    implementation(libs.libsu.core)
    implementation(libs.androidx.appcompat)

    // 压缩/解压
    implementation(libs.jxl.coder)                              // JPEG XL 图片压缩

    // WebDAV
    implementation("com.github.bitfireAT:dav4jvm:02fe1a95e6b86e323bec3784d7d2fe2d4081dde6") {
        exclude(group = "org.ogce", module = "xpp3")
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // libxposed API（编译时依赖，运行时由 Vector/LSPosed 框架提供）
    // 优先从 Maven Central 拉取，失败则使用仓库内本地源码编译
    val xposedApiVersion = "101.0.1"
    val xposedMavenCoord = "io.github.libxposed:api:$xposedApiVersion"
    val xposedMavenAvailable = try {
        val checkConfig = configurations.create("xposedMavenCheck") {
            isCanBeResolved = true
            isCanBeConsumed = false
        }
        dependencies.add(checkConfig.name, xposedMavenCoord)
        checkConfig.resolve()
        true
    } catch (_: Exception) {
        false
    }
    if (xposedMavenAvailable) {
        logger.lifecycle("libxposed API: 使用 Maven 依赖 ($xposedMavenCoord)")
        compileOnly(xposedMavenCoord)
    } else {
        logger.lifecycle("libxposed API: Maven 不可用，使用本地源码编译")
        implementation(project(":libs:libxposed-api"))
    }

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}