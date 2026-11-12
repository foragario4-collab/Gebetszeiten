plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gebetszeiten.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gebetszeiten.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "5.0"
    }

    signingConfigs {
        create("fest") {
            // Bevorzugt aus GitHub-Secrets (Umgebungsvariablen), sonst die Datei im Projekt
            val envStore = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
            storeFile = if (envStore != null && file(envStore).exists()) file(envStore) else file("gebetszeiten.keystore")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "gebetszeiten"
            keyAlias = "gebetszeiten"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "gebetszeiten"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fest")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fest")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.maplibre.gl:android-sdk:11.5.2")
}
