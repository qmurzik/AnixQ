plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Code shared by the TV app (:app) and the phone app (:mobile): AnimeLib API, library + sync,
// the live channel between devices, video engines and the upscaler.
android {
    namespace = "ru.qmods.anixq.core"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    api("androidx.core:core-ktx:1.13.1")
    api("androidx.media3:media3-exoplayer:1.5.1")
    api("androidx.media3:media3-exoplayer-hls:1.5.1")
    api("androidx.media3:media3-ui:1.5.1")
    api("androidx.media3:media3-effect:1.5.1")
    api("androidx.media3:media3-datasource-okhttp:1.5.1")
    api("io.coil-kt:coil:2.7.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    api("androidx.tvprovider:tvprovider:1.0.0")
}
