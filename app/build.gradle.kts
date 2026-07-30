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
fun localBuildConfigString(name: String): String = localProperties
    .getProperty(name, "")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

val supabaseUrl = localBuildConfigString("SUPABASE_URL")
val supabasePublishableKey = localBuildConfigString("SUPABASE_PUBLISHABLE_KEY")
val mcpGatewayUrl = localBuildConfigString("MCP_GATEWAY_URL")

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
        // The Supabase publishable key is intentionally client-safe. Secret
        // and service_role keys must never be added to the Android build.
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"$supabasePublishableKey\"",
        )
        buildConfigField("String", "MCP_GATEWAY_URL", "\"$mcpGatewayUrl\"")
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
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
