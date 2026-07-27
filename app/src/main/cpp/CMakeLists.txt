cmake_minimum_required(VERSION 3.22.1)

project("llamajni" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp는 서브모듈(app/src/main/cpp/llama.cpp)로 들어와 있다.
# 여기서 켜고 끄는 것들은 app/build.gradle.kts의 cmake arguments에서도
# 넘기지만, CMake 캐시가 아니라 소스에서 못을 박아두는 편이 재현이 쉽다.
add_subdirectory(llama.cpp build-llama)

add_library(llamajni SHARED llama_jni.cpp)

# common을 함께 쓴다. 여기 든 minja가 gguf 안의 지니자 템플릿을 그대로 렌더링한다.
# llama.h의 llama_chat_apply_template은 llama.cpp가 손으로 옮겨 적은 사본을 쓰는데,
# EXAONE 4.0 항목이 실제 템플릿과 달라(개행, [|endofturn|], 빈 <think> 블록 누락)
# 모델이 답 대신 추론을 시작한다.
target_include_directories(llamajni PRIVATE
        llama.cpp/common)

target_link_libraries(llamajni
        llama
        llama-common
        android
        log)
