plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("kotlin-parcelize") }
android {
    namespace = "com.flowai.communication"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.flowai.communication"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.3.0"
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // On-device Chinese OCR for screenshot capture (V2). The BUNDLED model is deliberate:
    // it ships inside the APK (about +4 MB per ABI), runs fully offline, and does NOT depend on
    // Google Play services — the unbundled variant downloads its model via GMS and returns empty
    // results until that finishes, which is unusable on devices without GMS.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
