plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chaska.inicio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chaska.inicio"
        minSdk = 30
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Firmado con la clave de depuración para instalar por adb sin más pasos.
            signingConfig = signingConfigs.getByName("debug")
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

// Sin librerías: solo Android y Kotlin, para gastar la mínima RAM (la TV tiene 1 GB).
dependencies {
}
