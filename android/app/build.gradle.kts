plugins {
    id("com.android.application")
}

android {
    namespace = "com.ok1cdj.atvrx"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ok1cdj.atvrx"
        minSdk = 28                       // aligned_alloc in the native ldpc_tool; requestWait(timeout) needs 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-f1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
