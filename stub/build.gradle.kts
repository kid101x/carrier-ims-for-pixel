plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "stub"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt())
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        aidl = true
    }
}
