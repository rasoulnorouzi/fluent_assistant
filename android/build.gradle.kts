// Single-module app: this folder *is* the app. AGP 9 compiles Kotlin itself (no Kotlin plugin needed).
plugins {
    id("com.android.application") version "9.4.1"
}

android {
    namespace = "app.tonefixer"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tonefixer"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    // tones.json / providers.json are shared with the browser extension and iOS.
    sourceSets["main"].assets.srcDir("../shared")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions.unitTests.isIncludeAndroidResources = true // Robolectric screenshot tests
}

dependencies {
    // Test-only: Android's org.json is a stub in JVM unit tests, so tests use the real one.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    // Test-only: renders real screens to PNG on the JVM (no emulator needed). Never ships in the app.
    testImplementation("org.robolectric:robolectric:4.17")
}
