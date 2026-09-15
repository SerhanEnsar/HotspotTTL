plugins {
    id("com.android.application")
}

android {
    namespace = "com.serhanensar.hotspotttl"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.serhanensar.hotspotttl"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
