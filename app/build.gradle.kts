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
        versionCode = 2
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
    // Nessuna dipendenza runtime esterna: HTTP, JSON, Keystore e scheduling sono API Android/JDK.
}
