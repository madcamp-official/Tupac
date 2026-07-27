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

target_link_libraries(llamajni
        llama
        android
        log)
