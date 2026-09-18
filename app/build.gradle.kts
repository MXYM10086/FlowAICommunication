plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.flowai.communication"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.flowai.communication"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.1.5"
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
    testImplementation("junit:junit:4.13.2")
}
