plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.whmdg.mczj.tools.accounting"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
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
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":others"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation("com.google.android.gms:play-services-location:21.3.0")
}

// ── 编译时检查：禁止非 Repository 文件直接访问 AccountingDatabase ──
tasks.register("checkAccountingDbAccess") {
    description = "检查记账模块中是否有代码直接访问 AccountingDatabase（应通过 Repository）"
    group = "verification"
    doLast {
        val accountingDir = file("src/main/java/com/whmdg/mczj/tools/ui/accounting")
        val allowedFiles = setOf("AccountingDatabase.kt", "AccountingRepository.kt")
        val violations = mutableListOf<String>()
        accountingDir.listFiles()?.filter { it.extension == "kt" }?.forEach { file ->
            if (file.name !in allowedFiles) {
                val content = file.readText()
                if (content.contains("AccountingDatabase")) {
                    violations.add("  ${file.name}: 直接引用了 AccountingDatabase，应使用 AccountingRepository")
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "记账模块数据库访问违规:\n${violations.joinToString("\n")}\n" +
                "所有对 AccountingDatabase 的访问必须通过 AccountingRepository 进行。"
            )
        }
    }
}
plugins.withId("org.jetbrains.kotlin.android") {
    tasks.matching { it.name.startsWith("compileKotlin") }.configureEach {
        dependsOn("checkAccountingDbAccess")
    }
}
