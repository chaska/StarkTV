import java.util.Properties

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
        versionCode = 65
        versionName = "0.9.40"
    }

    // Firma propia: si existe keystore.properties en la raíz (NUNCA va a GitHub, está en .gitignore),
    // ▶ Run y la release se firman con la misma clave y se pueden instalar una encima de otra.
    // Sin ese archivo, ▶ Run usa la clave de pruebas de Android Studio y la release va sin firmar.
    val datosFirma = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    val firmaPropia = datosFirma?.let { d: Properties ->
        signingConfigs.create("propia") {
            storeFile = rootProject.file(d.getProperty("storeFile"))
            storePassword = d.getProperty("storePassword")
            keyAlias = d.getProperty("keyAlias")
            keyPassword = d.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            if (firmaPropia != null) signingConfig = firmaPropia
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (firmaPropia != null) signingConfig = firmaPropia
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
