plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.gijimemo.whisper"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Whisper.cpp JNI: only arm64-v8a + x86_64
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Keep bundled GGML model binaries uncompressed in the APK.
    // 1) On Android 6+ (API 23+), assets/ entries with noCompress are stored
    //    uncompressed and can be mmapped directly from the APK, avoiding
    //    the 100+ MB inflate cost on first launch.
    // 2) On older devices without AAPT2 packaging, this still reduces APK size
    //    because the binary is already pseudo-random and incompressible.
    androidResources {
        noCompress += listOf("bin", "gguf")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // whisper.cpp native build via CMake
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    implementation(libs.okhttp)

    implementation(libs.kotlinx.coroutines.android)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
