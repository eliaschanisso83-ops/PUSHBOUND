plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pushbound.game"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pushbound.game"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // -----------------------------------------------------------------------
    // Release signing — environment driven, zero credentials in the repo.
    //
    // Every value below is a NAME read from the process environment, never a
    // literal. CI reconstructs the production keystore from the
    // ANDROID_KEYSTORE_BASE64 secret into a file OUTSIDE the workspace and
    // points ANDROID_KEYSTORE_PATH at it; the three credential secrets supply
    // the rest. That keystore is the permanent Pushbound signing identity —
    // nothing here creates or replaces it.
    //
    // With no environment set (every local build), no release signing config
    // is created at all, so the release buildType behaves exactly as it did
    // before this file was touched, and debug signing is untouched.
    // -----------------------------------------------------------------------
    val envStorePath = System.getenv("ANDROID_KEYSTORE_PATH") ?: ""
    val envStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
    val envKeyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: ""
    val envKeyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ""
    val releaseSigningReady = listOf(envStorePath, envStorePassword, envKeyAlias, envKeyPassword)
        .all { it.isNotBlank() }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                // rootProject.file() returns an absolute path unchanged, so a
                // runner temp file outside the repo and a repo-relative path
                // both resolve to the same file.
                storeFile = rootProject.file(envStorePath)
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
