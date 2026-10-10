plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from the web app: var APP_VERSION = 'x.y'
val appVersion: String = Regex("var APP_VERSION = '([0-9.]+)'")
    .find(rootProject.file("hwallet/web/index.html").readText())?.groupValues?.get(1) ?: "1.0"
val parts = appVersion.split(".").map { it.toIntOrNull() ?: 0 }
val code = (parts.getOrElse(0) { 1 }) * 10000 + (parts.getOrElse(1) { 0 }) * 100 + (parts.getOrElse(2) { 0 })

android {
    namespace = "app.hwallet"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.hwallet"
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
    // v1.2: WebSockets + HTTP for the always-on listener (foreground service)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // v1.3: the phone's own QR scanner for the Scan button (no camera permission; Google Play services)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // v1.4 (#53): JVM unit test of the shared data speed limit (HwRateTest)
    testImplementation("junit:junit:4.13.2")
}
