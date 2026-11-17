plugins {
    id("com.android.application")
}

android {
    namespace = "fr.zap.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.zap.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // Clé de signature fixe, fournie avec le projet : chaque nouvelle version
    // s'installe par-dessus l'ancienne sans perdre favoris ni playlists.
    signingConfigs {
        create("zap") {
            storeFile = file("zap-tv.keystore")
            storePassword = "zaptv-android"
            keyAlias = "zaptv"
            keyPassword = "zaptv-android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("zap")
        }
        debug {
            signingConfig = signingConfigs.getByName("zap")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
