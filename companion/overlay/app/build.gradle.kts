plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.idlearc.companion"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.idlearc.companion"
        minSdk = 24
        targetSdk = 34
        versionCode = 480
        versionName = "4.8.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // ML Kit добавим когда заведем OCR: implementation("com.google.mlkit:text-recognition:16.0.0")
}
