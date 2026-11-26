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
        versionCode = 3
        versionName = "1.2"
    }

    // Clé de signature fixe, fournie avec le projet : chaque nouvelle version
    // s'installe par-dessus l'ancienne sans perdre favoris ni sources.
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
        // nécessaire pour la bibliothèque WireGuard (VPN)
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // aligne toutes les versions de Kotlin tirées par les bibliothèques
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.9.24"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    // VPN intégré (WireGuard officiel)
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
