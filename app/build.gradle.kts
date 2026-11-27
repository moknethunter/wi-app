plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.mokasas.wikall"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mokasas.wikall"
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 6
        versionName = "0.4.0-rc2"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    System.getenv("WIKALL_DEBUG_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { key ->
        signingConfigs.getByName("debug") { storeFile = file(key) }
    }
}

kotlin.jvmToolchain(17)

kapt {
    correctErrorTypes = true
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.serialization.json)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(libs.room.runtime)
    implementation(libs.hilt.android)
    implementation(libs.webrtc)
    kapt(libs.room.compiler)
    kapt(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
