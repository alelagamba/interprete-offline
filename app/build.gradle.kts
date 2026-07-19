plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.translator.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.translator.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (findProperty("VERSION_CODE")?.toString()?.toIntOrNull() ?: 1)
        versionName = (findProperty("VERSION_NAME")?.toString() ?: "0.1.0-mvp")
        setProperty("archivesBaseName", "interprete-offline")
        ndk {
            // Public APK target: modern Android phones. This keeps the GitHub
            // download much smaller than packaging emulator/x86 ABIs too.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val keystore = System.getenv("SIGNING_KEYSTORE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
            )
            if (System.getenv("SIGNING_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // The speech SDK and sherpa-onnx AARs can expose the same runtime
            // libraries; keep one copy per ABI in the final APK.
            pickFirsts.add("lib/**/libonnxruntime.so")
            pickFirsts.add("lib/**/libc++_shared.so")
        }
    }
}

dependencies {
    implementation(files("libs/speech-sdk-release.aar"))
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.annotation:annotation:1.8.2")

    // ML Kit Translation — offline on-device translation
    implementation("com.google.mlkit:translate:17.0.3")

    // ML Kit Language ID — offline detection of the spoken language
    implementation("com.google.mlkit:language-id:17.0.6")

    // ML Kit Text Recognition (Latin, bundled) — offline OCR for photo translation
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // tar.bz2 extraction for the Piper voice packages
    implementation("org.apache.commons:commons-compress:1.26.2")
}
