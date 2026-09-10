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
        versionCode = 3
        versionName = "0.2.0"
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
    // No external runtime dependencies: HTTP, JSON, Keystore and scheduling use Android/JDK APIs.
}
