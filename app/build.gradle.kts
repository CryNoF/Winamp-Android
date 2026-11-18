plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cl.winampyt"
    compileSdk = 34

    defaultConfig {
        applicationId = "cl.winampyt"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Reemplaza a yt-dlp: busca y obtiene el audio de YouTube. Si deja de funcionar,
    // sube a la última versión: https://github.com/TeamNewPipe/NewPipeExtractor/releases
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
}
