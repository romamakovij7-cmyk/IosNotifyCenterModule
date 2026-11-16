plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.iosshade.systemui"
    compileSdk = 36 // One UI 9 = Android 16

    defaultConfig {
        applicationId = "com.iosshade.systemui"
        minSdk = 35 // Galaxy S24 FE ships Android 15/16; SystemUI hooks run in-process
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-mvp"
    }

    buildTypes {
        release {
            // Keep minify OFF: R8 must never rename MainHook (referenced from assets/xposed_init)
            // or any hook entry class.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Xposed API is compile-only: LSPosed provides de.robv.android.xposed.* at runtime
    // inside the com.android.systemui process. Never package it into the APK.
    compileOnly("de.robv.android.xposed:api:82")
}
