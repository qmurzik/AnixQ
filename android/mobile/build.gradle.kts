plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "ru.qmods.anixq.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.qmods.anixq.mobile"
        minSdk = 23
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // Same key as the TV app (repository secret or the cached CI key), so updates install over each other.
        create("release") {
            val ks = System.getenv("ANDROID_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "anixq"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(project(":core"))
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.media3:media3-session:1.5.1")
}

// Release updates must use the repository's existing key, restored by android-tv.yml.
// A local debug key cannot update an installed release and must never be substituted.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        val signingPath = System.getenv("ANDROID_KEYSTORE")
        check(!signingPath.isNullOrBlank() && file(signingPath).isFile) {
            "Release signing requires ANDROID_KEYSTORE. Use the Android TV GitHub Actions workflow, which restores the existing signing key."
        }
        check(!System.getenv("ANDROID_KEYSTORE_PASSWORD").isNullOrBlank()) {
            "Release signing requires ANDROID_KEYSTORE_PASSWORD."
        }
    }
}
