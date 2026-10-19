plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.soundlock"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.soundlock"
        minSdk = 34
        targetSdk = 35
        versionCode = 10
        versionName = "0.1.9"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.foundation:foundation:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    testImplementation("junit:junit:4.13.2")
}
