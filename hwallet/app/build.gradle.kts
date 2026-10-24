import java.security.SecureRandom

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from the web app: var APP_VERSION = 'x.y'
val appVersion: String = Regex("var APP_VERSION = '([0-9.]+)'")
    .find(rootProject.file("hwallet/web/index.html").readText())?.groupValues?.get(1) ?: "1.0"
val parts = appVersion.split(".").map { it.toIntOrNull() ?: 0 }
val code = (parts.getOrElse(0) { 1 }) * 10000 + (parts.getOrElse(1) { 0 }) * 100 + (parts.getOrElse(2) { 0 })


// v1.6 (#57/#58/#71): the owner's API keys serve every user, but never sit in the public source.
// CI passes them as GitHub Actions secrets (KW_*). At build time each one is sealed with AES-GCM under a
// fresh random per-build key; that key is split into four masked pieces spread over two generated classes.
// Hwallet opens a key only in memory, natively, and adds it to requests itself, so the page never sees it.
// A missing secret simply leaves that provider keyless. This is obfuscation, not a vault (see SECURITY.md).
val hwSecretNames = linkedMapOf(
    "alchemy" to "KW_ALCHEMY_KEY", "etherscan" to "KW_ETHERSCAN_KEY", "helius" to "KW_HELIUS_KEY",
    "trongrid" to "KW_TRONGRID_KEY", "lifi" to "KW_LIFI_KEY", "jupiter" to "KW_JUPITER_KEY", "reown" to "KW_REOWN_PROJECT_ID"
)
val hwGenDir = layout.buildDirectory.dir("generated/hwsecrets/kotlin")
val genHwSecrets = tasks.register("genHwSecrets") {
    outputs.dir(hwGenDir)
    outputs.upToDateWhen { false }
    doLast {
        val rnd = SecureRandom()
        val key = ByteArray(32).also { rnd.nextBytes(it) }
        val p1 = ByteArray(32).also { rnd.nextBytes(it) }; val p2 = ByteArray(32).also { rnd.nextBytes(it) }; val p3 = ByteArray(32).also { rnd.nextBytes(it) }
        val p4 = ByteArray(32) { i -> (key[i].toInt() xor p1[i].toInt() xor p2[i].toInt() xor p3[i].toInt()).toByte() }
        val salt = rnd.nextInt(200) + 17
        fun lit(b: ByteArray, m: Int) = b.joinToString(",") { (((it.toInt() and 0xff) + m) and 0xff).toString() }
        val rows = StringBuilder(); var n = 0
        for ((name, env) in hwSecretNames) {
            val v = (System.getenv(env) ?: "").trim()
            if (v.isEmpty()) continue
            val iv = ByteArray(12).also { rnd.nextBytes(it) }
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
            c.updateAAD(name.toByteArray())
            val ct = c.doFinal(v.toByteArray())
            rows.append("        \"").append(name).append("\" to intArrayOf(").append(lit(iv + ct, 0)).append("),\n"); n++
        }
        val dir = hwGenDir.get().asFile.resolve("app/hwallet"); dir.mkdirs()
        dir.resolve("HwSa.kt").writeText("package app.hwallet\n\n/* generated at build time, never committed */\ninternal object HwSa {\n" +
            "    @JvmField val q = intArrayOf(${lit(p1, salt)})\n    @JvmField val w = intArrayOf(${lit(p3, 255 - salt)})\n    @JvmField val m = $salt\n" +
            "    @JvmField val d: Map<String, IntArray> = mapOf(\n$rows    )\n}\n")
        dir.resolve("HwSb.kt").writeText("package app.hwallet\n\n/* generated at build time, never committed */\ninternal object HwSb {\n" +
            "    @JvmField val e = intArrayOf(${lit(p2, 0)})\n    @JvmField val r = intArrayOf(${lit(p4, 101)})\n}\n")
        println("Hwallet: sealed $n of ${hwSecretNames.size} provider keys into this build")
    }
}
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach { dependsOn(genHwSecrets) }

android {
    namespace = "app.hwallet"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.hwallet"
        minSdk = 24
        targetSdk = 34
        versionCode = code
        versionName = appVersion
        // v3.97 P0: true only in the CI emulator job (-PkwE2E=true): lets the device check drive the WebViews over DevTools
        buildConfigField("boolean", "E2E", if (project.findProperty("kwE2E") == "true") "true" else "false")
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
            // v1.9.19: the shipped APK carries phone ABIs only (arm64-v8a + armeabi-v7a): smaller download. The CI emulator
            // build (-PkwE2E=true) keeps x86_64 so it still installs on the x86_64 emulator; debug builds are unfiltered as before.
            // The VPN binaries (usque, Psiphon) exist for arm64-v8a only.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                if (project.findProperty("kwE2E") == "true") abiFilters += "x86_64"
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") { assets.srcDirs("src/main/assets", "../web"); java.srcDir(hwGenDir) }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // v1.8.12 (push 17): the native Monero engine (monero_c wallet2, ~30 MB per ABI) is stored compressed in the APK and
        // extracted at install (much smaller download); CI puts the verified, stripped libraries in src/main/jniLibs
        jniLibs.useLegacyPackaging = true
        // v1.9.19: libusque.so and libpsiphon.so are the VPN executables (CI puts them in jniLibs/arm64-v8a). Legacy
        // packaging extracts it to nativeLibraryDir, the one app path Android 10+ lets an app execute from.
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    // v1.2: WebSockets + HTTP for the always-on listener (foreground service)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // v1.9.19: Secure DNS (DNS over HTTPS: Quad9 / Cloudflare / Google / AdGuard) for the shared client, same version as okhttp
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    // v1.5 (#61): Hwallet's own scanner: CameraX preview + ZXing decoding on the phone (no Play services)
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.zxing:core:3.5.3")
    // v1.8.12 (push 17, R1): JNA calls monero_c's C API (libmonero_wallet2_api_c.so) without any C code of ours
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    // v1.4 (#53): JVM unit test of the shared data speed limit (HwRateTest)
    testImplementation("junit:junit:4.13.2")
}
