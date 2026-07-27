import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}
val geminiApiKey = localProperties
    .getProperty("GEMINI_API_KEY", "")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.example.mobileguiagent"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.mobileguiagent"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // The PoC targets the connected Samsung phone and the current arm64
        // emulator. Avoid packaging four copies of large OCR/STT native
        // runtimes into every debug APK.
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Keep the value out of source control. Developers may define
        // GEMINI_API_KEY in the untracked root local.properties file.
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs.pickFirsts += "**/libonnxruntime.so"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.openwakeword.android)
    // Bundled Korean OCR avoids first-use model download variance in the
    // on-device latency benchmark and remains available offline.
    implementation(libs.mlkit.text.recognition.korean)
    // sherpa-onnx 1.13.4 ships an ONNX Runtime 1.27 core. Keep the Java
    // bindings on the same ABI so SenseVoice and openWakeWord can coexist.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation(libs.moonshine.voice)
    // Experimental Android-native LLM backend. It is kept beside llama.cpp so
    // we can benchmark the same tool-planning prompt without changing the
    // production model path until device measurements justify a migration.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    // LiteRT-LM 0.14's Kotlin callback was compiled against the modern
    // SendChannel default-method ABI. AndroidX otherwise resolves 1.9.0,
    // which crashes at the end of the first streamed response.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
