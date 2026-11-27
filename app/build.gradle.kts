plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.isaakrichards.instaban"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.isaakrichards.instaban"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK installs straight away.
            // Swap in your own signingConfig if you ever distribute it.
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

// No AndroidX / no third-party libraries: just the platform WebView.
