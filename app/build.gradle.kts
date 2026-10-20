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
        // Numéro de build automatique = numéro du run GitHub Actions (GITHUB_RUN_NUMBER,
        // croissant) ; 1 en build local. Visible dans le menu admin → « À propos ».
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"

        // Code demandé avant de changer l'URL. Fourni par le secret GitHub URL_PIN
        // (le dépôt est public : il ne doit jamais être écrit dans le code).
        // Vide → aucun code demandé.
        val urlPin = (System.getenv("URL_PIN") ?: "").filter { it.isLetterOrDigit() }
        buildConfigField("String", "URL_PIN", "\"$urlPin\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            // Renseigné en CI via des variables d'environnement (secrets GitHub).
            // Absent en local → build release non signé.
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
