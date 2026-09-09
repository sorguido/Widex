plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.poc.codexlimits"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.poc.codexlimits"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-poc"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // PoC intenzionalmente senza librerie HTTP/JSON/crypto esterne.
}