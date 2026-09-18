plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("kotlin-parcelize") }
android {
    namespace = "com.flowai.communication"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.flowai.communication"
        minSdk = 26
        targetSdk = 34
        versionCode = 26
        versionName = "1.0.4"

        ndk {
            // The bundled ML Kit OCR pipeline ships a 7-12 MB native library PER ABI; shipping all
            // four made the APK 50.7 MB. arm64-v8a covers every real device this targets, and the
            // test emulator runs it through ARM translation (ro.enable.native.bridge.exec=1).
            abiFilters += listOf("arm64-v8a")
        }
    }
    lint {
        // ChromeOS is not a target platform; shipping x86_64 purely to satisfy this would re-add
        // ~12 MB of native OCR library for no user.
        disable += "ChromeOsAbiSupport"
    }
    buildTypes {
        debug {
            // Lets a developer point the app at a relay running on their own machine, whose
            // certificate story is not the point of the test. Release builds do not reference this
            // config and stay https-only.
            manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config"
        }
        release {
            manifestPlaceholders["networkSecurityConfig"] = ""
        }
    }
    buildFeatures {
        compose = true
        // The remote engine gates cleartext-to-localhost on DEBUG, so it needs this class.
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests {
            // Remote engine tests reach real code paths that log. Without this, android.util.Log is
            // an unmocked stub and the test fails on the logging rather than on the logic.
            isReturnDefaultValues = true
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // On-device Chinese OCR for screenshot capture (V2). The BUNDLED model is deliberate:
    // it ships inside the APK, runs fully offline, and does NOT depend on Google Play services —
    // the unbundled variant downloads its model via GMS and returns empty results until that
    // finishes, which is unusable on devices without GMS.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    // Engine calls are suspending so a network-backed implementation can satisfy the same seam.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // The platform's org.json is a stub in JVM tests; this gives the real parser so the remote
    // engine's request building and response parsing are genuinely exercised.
    testImplementation("org.json:json:20240303")
}
