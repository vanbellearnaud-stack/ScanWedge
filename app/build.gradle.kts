plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vba.robotscan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vba.robotscan"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
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
}

dependencies {
    // Aucune dépendance AndroidX : Activity + WebView de la plateforme suffisent.
    // org.json est fourni par le SDK Android (android.jar).
}
