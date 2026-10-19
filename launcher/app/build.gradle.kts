import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release key: keystore.properties (gitignored) next to settings.gradle.kts with
// storeFile, storePassword, keyAlias, keyPassword. storeFile is resolved relative to the project root.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "io.couchkit.home"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.couchkit.home"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        // What releases ship. Contains no account/cloud code at all.
        create("standard") {
            dimension = "edition"
            isDefault = true
        }
        // Off by default, never published as a release. See extras/experimental-batteries-bridge/README.md.
        create("experimentalBatteries") {
            dimension = "edition"
            versionNameSuffix = "-experimental"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without keystore.properties the release APK is signed with the local debug key.
            // That is only meant for sideloading onto your own TV, not for distribution.
            signingConfig = if (keystoreProps != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Video playback for the screensaver / featured-row background (local files, mkv/webm support).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
}
