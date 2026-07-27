plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 온디바이스 LLM 타당성 확인용. 테스트 기기(S10e)가 arm64-v8a 하나뿐이라
        // 다른 ABI는 빌드하지 않는다 — x86_64를 켜면 빌드 시간만 두 배가 된다.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                // 정적 링크로 libllamajni.so 하나만 만든다. 공유 라이브러리로
                // 빌드하면 libllama.so / libggml*.so 가 나오는데, PoC가 이미
                // app/src/main/jniLibs/arm64-v8a 에 같은 이름의 프리빌트를
                //들고 있어서 패키징 단계에서 그대로 부딪힌다.
                arguments += "-DBUILD_SHARED_LIBS=OFF"

                // 앱에 필요한 건 libllama뿐이다. 나머지 산출물은 다 끈다.
                arguments += "-DLLAMA_BUILD_COMMON=OFF"
                arguments += "-DLLAMA_BUILD_TOOLS=OFF"
                arguments += "-DLLAMA_BUILD_EXAMPLES=OFF"
                arguments += "-DLLAMA_BUILD_TESTS=OFF"
                arguments += "-DLLAMA_BUILD_SERVER=OFF"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_OPENSSL=OFF"

                // GGML_NATIVE는 빌드 머신(맥) 기준으로 최적화 플래그를 잡는다.
                // 크로스 컴파일이므로 반드시 꺼야 한다.
                arguments += "-DGGML_NATIVE=OFF"
                // KleidiAI는 설정 단계에서 외부 소스를 받아온다. 확인 단계에서
                // 네트워크 의존을 늘리지 않는다.
                arguments += "-DGGML_CPU_KLEIDIAI=OFF"
                // 백엔드를 별도 .so로 떼어내면 런타임 로딩 경로가 하나 더 는다.
                // 지금은 libggml 안에 CPU 백엔드를 그대로 넣는다.
                arguments += "-DGGML_BACKEND_DL=OFF"
                arguments += "-DGGML_CPU_ALL_VARIANTS=OFF"
                // baseline arm64-v8a로 두면 dotprod 커널을 못 쓴다. S10e는
                // asimddp를 갖고 있다. 다만 이건 armv8.2 미만 기기를 버리는
                // 선택이라, 실제로 붙일 때는 GGML_CPU_ALL_VARIANTS 쪽을 봐야 한다.
                arguments += "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16"
                // REPACK은 가중치를 런타임에 다시 깔아 속도를 얻는 대신 mmap
                // 사본을 익명 메모리로 한 벌 더 든다. S10e에서는 그 값이 크다.
                arguments += "-DGGML_CPU_REPACK=OFF"
                arguments += "-DGGML_OPENMP=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
            }
        }
    }

    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        // UiNode가 android.graphics.Rect를 들고 있다. 유닛 테스트의 android.jar는
        // 껍데기라 그대로 부르면 "Stub!" 예외가 난다. 여기서 재는 것은 칸 배정
        // 규칙이고 bounds는 쓰지 않으므로, 기본값을 돌려주게 해서 통과시킨다.
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
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
    implementation(libs.moonshine.voice)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
