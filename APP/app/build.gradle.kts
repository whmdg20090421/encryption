import java.net.HttpURLConnection
import java.net.URL
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ── TND 二进制自动更新任务 ──
val tndBinaryDir = file("src/main/jniLibs/arm64-v8a")
val tndBinaryFile = File(tndBinaryDir, "libtnd.so")
val tndVersionFile = File(tndBinaryDir, "tnd_version.txt")

// 读取本地版本
fun getLocalVersion(): String {
    return if (tndVersionFile.exists()) {
        tndVersionFile.readText().trim()
    } else {
        "0.0.0"
    }
}

// 获取 GitHub 最新 release 版本
fun getLatestRelease(): Pair<String, String>? {
    return try {
        val url = URL("https://api.github.com/repos/zhongbai2333/Tomato-Novel-Downloader/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        if (conn.responseCode == 200) {
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            // 简单解析 tag_name 和下载 URL
            val tagMatch = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(json)
            val tag = tagMatch?.groupValues?.get(1) ?: return null

            // 找到 Android arm64的下载链接
            val urlMatch = Regex(""""browser_download_url"\s*:\s*"([^"]*Android_arm64[^"]*)"""").find(json)
            val downloadUrl = urlMatch?.groupValues?.get(1) ?: return null

            Pair(tag, downloadUrl)
        } else {
            conn.disconnect()
            null
        }
    } catch (e: Exception) {
        println("[TND] 检查最新版本失败: ${e.message}")
        null
    }
}

// 版本比较（v2.4.13 -> 2.4.13）
fun compareVersions(v1: String, v2: String): Int {
    val clean1 = v1.removePrefix("v")
    val clean2 = v2.removePrefix("v")
    val parts1 = clean1.split(".").map { it.toIntOrNull() ?: 0 }
    val parts2 = clean2.split(".").map { it.toIntOrNull() ?: 0 }

    for (i in 0 until maxOf(parts1.size, parts2.size)) {
        val p1 = parts1.getOrElse(i) { 0 }
        val p2 = parts2.getOrElse(i) { 0 }
        if (p1 != p2) return p1.compareTo(p2)
    }
    return 0
}

// 下载文件
fun downloadFile(url: String, dest: File): Boolean {
    return try {
        println("[TND] 正在下载: $url")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 60000

        if (conn.responseCode == 200) {
            val tmpFile = File(dest.parentFile, "${dest.name}.tmp")
            conn.inputStream.use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()

            // 下载成功，替换目标文件
            if (dest.exists()) dest.delete()
            tmpFile.renameTo(dest)
            true
        } else {
            conn.disconnect()
            false
        }
    } catch (e: Exception) {
        println("[TND] 下载失败: ${e.message}")
        false
    }
}

// 更新任务
tasks.register("updateTndBinary") {
    description = "检查并更新 TND 二进制文件到最新版本"
    group = "tnd"

    doLast {
        val localVersion = getLocalVersion()
        println("[TND] 本地版本: $localVersion")

        val release = getLatestRelease()
        if (release == null) {
            println("[TND] 无法获取最新版本信息，使用本地版本")
            return@doLast
        }

        val (latestVersion, downloadUrl) = release
        println("[TND] 最新版本: $latestVersion")

        val comparison = compareVersions(localVersion, latestVersion)
        if (comparison >= 0) {
            println("[TND] 本地版本已是最新，跳过更新")
            return@doLast
        }

        println("[TND] 发现新版本 $latestVersion，开始下载...")

        // 旧版本备份目录
        val oldVersionsDir = File(tndBinaryDir, "旧版本")
        if (!oldVersionsDir.exists()) oldVersionsDir.mkdirs()

        // 下载新版本到临时文件
        val tmpFile = File(tndBinaryDir, "libtnd.so.tmp")
        val success = downloadFile(downloadUrl, tmpFile)

        if (success) {
            // 将当前版本移入旧版本目录（保留版本号命名）
            if (tndBinaryFile.exists() && localVersion != "0.0.0") {
                val oldVersionFile = File(oldVersionsDir, "libtnd_${localVersion}.so")
                tndBinaryFile.copyTo(oldVersionFile, overwrite = true)
                tndBinaryFile.delete()
                println("[TND] 旧版本已备份: ${oldVersionFile.name}")
            }

            // 将新版本移到正式位置
            tmpFile.renameTo(tndBinaryFile)

            // 更新版本文件
            tndVersionFile.writeText(latestVersion)
            println("[TND] 更新成功: $localVersion -> $latestVersion")
        } else {
            // 下载失败，删除临时文件，保持本地版本不变
            if (tmpFile.exists()) tmpFile.delete()
            println("[TND] 下载失败，使用本地版本: $localVersion")
        }
    }
}

// 让编译任务依赖更新任务
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("updateTndBinary")
}

android {
    namespace = "com.whmdg.mczj.tools"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.whmdg.mczj.tools"
        minSdk = 26
        targetSdk = 36
        val gitCommitCount = try {
            val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            proc.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 0
        } catch (_: Exception) { 0 }
        versionCode = 1700000000 + gitCommitCount
        val ts = System.currentTimeMillis() / 1000
        versionName = "4.3.%d".format(ts)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("APP/app/release-keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val isCompact = project.hasProperty("compact")
            val proguardFile = if (isCompact) "proguard-compact.pro" else "proguard-rules.pro"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), proguardFile)
            val envOk = System.getenv("KEYSTORE_PASSWORD")?.isNotEmpty() == true &&
                        System.getenv("KEY_ALIAS")?.isNotEmpty() == true
            signingConfig = if (envOk) signingConfigs.getByName("release")
                           else signingConfigs.getByName("debug")
        }
    }

    lint {
        checkReleaseBuilds = false
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
            // 使用旧版打包，压缩 .so 文件减小 APK 体积
            useLegacyPackaging = true
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
    implementation(project(":APP:core"))
    implementation(project(":APP:Models:accounting"))
    implementation(project(":APP:Models:others"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.libsu.core)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
