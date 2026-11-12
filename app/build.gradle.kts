plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.che.ls05"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.che.ls05"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // Sabit imza: CI anahtarı .signing/ altında üretir ve önbellekte tutar (depoya girmez, .gitignore).
    // Dosya yoksa (ör. yerel Android Studio) varsayılan test anahtarı kullanılır.
    signingConfigs {
        getByName("debug") {
            val ks = rootProject.file(".signing/ls05-debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
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
    implementation(project(":protocol"))
}
