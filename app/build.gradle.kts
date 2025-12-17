plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screensense"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screensense"
        minSdk = 29  // Android 10+ for better MediaProjection handling
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        
        // Required for sherpa-onnx native libs
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Sherpa-ONNX for Piper TTS
    implementation(files("libs/sherpa-onnx-1.12.19.aar"))
    
    // ML Kit - On-Device OCR (for future screenshot feature)
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.1")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    // Material Design
    implementation("com.google.android.material:material:1.10.0")

    // ConstraintLayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
