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
        versionCode = 50
        versionName = "0.9.25"
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

// Casi sin librerías, para gastar la mínima RAM (la TV tiene 1 GB). La única: dadb (Apache 2.0),
// para que Stark TV hable con la depuración de la propia tele y pueda colocar las ventanas del PAP.
dependencies {
    implementation("dev.mobile:dadb:1.2.9") {
        // dadb publica por error sus librerías de pruebas como dependencias: fuera.
        exclude(group = "org.junit.platform")
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.opentest4j")
        exclude(group = "org.apiguardian")
    }
}
