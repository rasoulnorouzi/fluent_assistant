import java.util.Properties

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
        // CI run number keeps versionCode increasing, so every new APK installs over the old one.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = System.getenv("GITHUB_REF_NAME")?.takeIf { it.startsWith("v") }?.removePrefix("v") ?: "0.1.$run"
    }

    // Release signing: from env vars (GitHub secrets in CI) or android/keystore.properties locally.
    // The same key must sign every release, or Android refuses to update the app. Never commit it.
    val props = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun secret(name: String): String? = System.getenv(name) ?: props.getProperty(name)
    val storePath = secret("TF_KEYSTORE_FILE")
    signingConfigs {
        if (storePath != null) create("release") {
            storeFile = file(storePath)
            storePassword = secret("TF_KEYSTORE_PASSWORD")
            keyAlias = secret("TF_KEY_ALIAS")
            keyPassword = secret("TF_KEY_PASSWORD")
        }
    }

    // tones.json / providers.json are shared with the browser extension and iOS.
    sourceSets["main"].assets.srcDir("../shared")

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
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
