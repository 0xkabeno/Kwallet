plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from the web app: const APP_VERSION = 'x.y'
val appVersion: String = Regex("const APP_VERSION = '([0-9.]+)'")
    .find(rootProject.file("web/index.html").readText())?.groupValues?.get(1) ?: "1.0"
val parts = appVersion.split(".").map { it.toIntOrNull() ?: 0 }
val code = (parts.getOrElse(0) { 1 }) * 10000 + (parts.getOrElse(1) { 0 }) * 100 + (parts.getOrElse(2) { 0 })

android {
    namespace = "app.kwallet"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.kwallet"
        minSdk = 24
        targetSdk = 34
        versionCode = code
        versionName = appVersion
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KW_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storeType = if (ks.endsWith(".p12")) "pkcs12" else "jks"
                storePassword = System.getenv("KW_STORE_PASSWORD")
                keyAlias = System.getenv("KW_KEY_ALIAS")
                keyPassword = System.getenv("KW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") { assets.srcDirs("src/main/assets", "../web") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
}
