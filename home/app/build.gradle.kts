import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi")
}

// Secrets stay out of git: local.properties (gitignored) may set tmdb.key=<TMDB v3 API key>.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.barontv.home"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.barontv.home"
        // The Baron TV runs Android 9 (API 28), and only that TV runs this.
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        // CloudWalker's App Store reads the Home app's versionName as <version>-<build>-<commit>-<type> and
        // crashes at start on anything shorter (CwHeaders splits it on "-" and takes the fourth part).
        versionName = "1.0-0-baronhome-lite-com"
        // Empty: the shelf uses TVmaze, which needs no key.
        buildConfigField("String", "TMDB_KEY", "\"${localProps.getProperty("tmdb.key", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // R8 strips and optimises the code: a smaller APK and a faster start on the TV's slow CPU.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded over ADB, never published: signed with the debug key, as the launcher is.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Framework views only, no AndroidX: the lightest home screen for a 512 MB TV.
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
