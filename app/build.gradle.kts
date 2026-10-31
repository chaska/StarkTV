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
        versionCode = 61
        versionName = "0.9.36"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sin firma aquí: la release se firma con la clave propia de ChasKa (Generate Signed APK), que no está en el repositorio.
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

// El APK de release sale como StarkTV-<versión>.apk (también con «Generate Signed APK»).
android.applicationVariants.all {
    val variante = this
    if (variante.buildType.name == "release") variante.outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "StarkTV-${variante.versionName}.apk"
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
