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
        versionCode = 2
        versionName = "0.2-f3"
    }
    // leandvb and ldpc_tool are executables shipped as lib*.so (android/native/build-native.sh): they must be
    // extracted to nativeLibraryDir, the one place an app may exec files from
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// the native receiver tools are built outside Gradle (they come from third_party/ESP32-DATV-RX/build)
tasks.named("preBuild") {
    doFirst {
        val dir = file("src/main/jniLibs/arm64-v8a")
        if (!File(dir, "libleandvb.so").exists() || !File(dir, "libldpc_tool.so").exists())
            throw GradleException("missing leandvb / ldpc_tool: run android/native/build-native.sh")
    }
}
