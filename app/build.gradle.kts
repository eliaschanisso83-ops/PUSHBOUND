plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// Google AdMob identifiers.
//
// THESE ARE PUBLISHABLE IDENTIFIERS, NOT SECRETS. An AdMob App ID and an ad
// unit ID are compiled into every APK and can be read out of it by anyone who
// downloads the app; Google documents them as values you are expected to ship
// in source. They are therefore in a versioned file on purpose, and they are
// entirely separate from the signing material governed by the rules below.
//
// DEVELOPMENT AND QA USE GOOGLE'S OWN DEMO INVENTORY. The defaults are Google's
// demo App ID and demo REWARD unit, so a debug build can exercise the whole
// hint-for-an-ad flow as often as anyone likes without ever touching live
// inventory or risking invalid activity on the production account. Only the
// `release` build type explicitly opts into the production pair below.
//
// Pushbound_Rewarded_Hint (ca-app-pub-5147549053838908/7178497807) is the one
// and only ad unit in the product. There is no banner, no interstitial and no
// app-open unit, by design.
// ---------------------------------------------------------------------------
val ADMOB_DEMO_APP_ID = "ca-app-pub-3940256099942544~3347511713"
val ADMOB_DEMO_REWARDED_UNIT = "ca-app-pub-3940256099942544/5224354917"
val ADMOB_PRODUCTION_APP_ID = "ca-app-pub-5147549053838908~5350410819"
val ADMOB_PRODUCTION_REWARDED_UNIT = "ca-app-pub-5147549053838908/7178497807"

android {
    namespace = "com.pushbound.game"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pushbound.game"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Safe-by-default: unless a build type says otherwise, ads resolve to
        // Google's demo inventory. Nothing can reach production by omission.
        manifestPlaceholders["admobAppId"] = ADMOB_DEMO_APP_ID
        buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"$ADMOB_DEMO_REWARDED_UNIT\"")
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

            // THE ONLY PLACE PRODUCTION AD INVENTORY IS WIRED IN. Reaching this
            // block means the build is signed with the permanent production
            // keystore and is a release artifact, which is what "ready for real
            // production advertising" means here.
            manifestPlaceholders["admobAppId"] = ADMOB_PRODUCTION_APP_ID
            buildConfigField(
                "String",
                "REWARDED_AD_UNIT_ID",
                "\"$ADMOB_PRODUCTION_REWARDED_UNIT\""
            )
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
        // Needed for BuildConfig.REWARDED_AD_UNIT_ID, which is how the ad unit
        // is selected per build type without putting an identifier in source
        // that debug builds could accidentally ship.
        buildConfig = true
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

    // -----------------------------------------------------------------------
    // Advertising — REWARDED ONLY.
    //
    // play-services-ads brings the ad formats; Pushbound uses exactly one of
    // them, on the Pushbound_Rewarded_Hint unit. Nothing in this file activates
    // a format: the SDK being on the classpath does not show an ad, and the
    // only code that can is [AdMobAdsGateway], reached solely through a player
    // confirming the hint offer.
    //
    // WHY 24.4.0 AND NOT A NEWER ONE. This project compiles with Kotlin
    // 2.0.21, which reads Kotlin binary metadata up to 2.0.0 and no further.
    // play-services-ads 25.5.0 ships metadata 2.3.0 and 24.9.0 ships 2.2.0, so
    // both fail script compilation outright. 24.4.0 is the newest release that
    // both compiles against this toolchain and declares no kotlin-stdlib of its
    // own — 24.6.0 also compiles, but drags in kotlin-stdlib 2.1.0, which would
    // put the same incompatible metadata back on the classpath through Gradle's
    // version resolution. Verified with the project's own vendored kotlinc.
    //
    // The ceiling is the compiler, not the ad SDK: raising the Kotlin version
    // is what would unlock a newer SDK, and that is a toolchain decision to be
    // made on its own merits rather than as a side effect of adding ads.
    //
    // user-messaging-platform (UMP) is Google's consent SDK. It is required to
    // request ads lawfully in regulated regions, and it is deliberately invoked
    // LAZILY on the first confirmed offer rather than at launch, so the game's
    // opening frames are never interrupted by it.
    // -----------------------------------------------------------------------
    implementation("com.google.android.gms:play-services-ads:24.4.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")

    testImplementation("junit:junit:4.13.2")
}
