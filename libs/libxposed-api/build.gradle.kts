plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.libxposed.api.local"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources.enable = false
    buildFeatures.buildConfig = false
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.10.0")
    compileOnly("io.github.libxposed:annotation:1.0.0")
}
