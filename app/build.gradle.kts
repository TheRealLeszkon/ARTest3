plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.armeasure"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.armeasure"
        minSdk = 26          // ARCore Depth realistically targets newer devices; simplifies image APIs
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-poc"

        // The bulk of the APK is Filament + ARCore + ONNX native libs. Target only arm64-v8a (the
        // OnePlus test device) — keeps the APK small and stops the flaky USB install from timing out.
        // (Drop this filter if you ever target x86 emulators / older ABIs.)
        ndk {
            abiFilters += "arm64-v8a"
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

    buildFeatures {
        compose = true
    }
    composeOptions {
        // Compose compiler matched to Kotlin 1.9.25.
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // AR rendering — SceneView (bundles ARCore + Filament) with the Compose ARScene API.
    implementation("io.github.sceneview:arsceneview:2.2.1")

    // On-device inference for MobileSAM (encoder + prompt decoder). NNAPI + CPU fallback.
    // Only the arm64-v8a native lib ships (see abiFilters).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
}
