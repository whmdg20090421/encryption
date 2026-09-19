plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.rosemoe.sora.langs.java"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    compileOnly(project(":libs:sora-editor:editor"))
    api("androidx.annotation:annotation:1.10.0")
}
