#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
source_root="${1:?Pass the OpenBMB/MiniCPM-V-Apps checkout as the first argument}"
ndk_root="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r27}"
cmake_bin="${CMAKE_BIN:-$(command -v cmake)}"
glslc_bin="${GLSLC_BIN:-$(command -v glslc)}"
vulkan_headers="${VULKAN_HEADERS_ROOT:-$(brew --prefix vulkan-headers)}"
native_source="$source_root/MiniCPM-V-demo-Android/app/src/main/cpp"
build_dir="${VULKAN_BUILD_DIR:-$project_root/.native-build/vulkan-arm64}"
destination="$project_root/app/src/main/jniLibs/arm64-v8a"
patch_file="$project_root/native/vulkan/llama-gpu-layers.patch"
spirv_config="$project_root/native/vulkan"
vulkan_library="$ndk_root/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/29/libvulkan.so"
strip_bin="$ndk_root/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"

if git -C "$source_root" apply --check "$patch_file" 2>/dev/null; then
    git -C "$source_root" apply "$patch_file"
fi

"$cmake_bin" -S "$native_source" -B "$build_dir" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk_root/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DLLAMA_BUILD_COMMON=ON \
    -DLLAMA_BUILD_TOOLS=ON \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_CURL=OFF \
    -DGGML_NATIVE=OFF \
    -DGGML_LLAMAFILE=ON \
    -DGGML_VULKAN=ON \
    -DVulkan_GLSLC_EXECUTABLE="$glslc_bin" \
    -DVulkan_LIBRARY="$vulkan_library" \
    -DSPIRV-Headers_DIR="$spirv_config" \
    -DCMAKE_CXX_FLAGS="-I$vulkan_headers/include"

"$cmake_bin" --build "$build_dir" --target minicpm_v_demo -j 8

mkdir -p "$destination"
for library in \
    libggml-base.so \
    libggml-cpu.so \
    libggml-vulkan.so \
    libggml.so \
    libllama-common.so \
    libllama.so \
    libmtmd.so
do
    cp "$build_dir/bin/$library" "$destination/$library"
    "$strip_bin" --strip-unneeded "$destination/$library"
done

cp "$build_dir/libminicpm_v_demo.so" "$destination/libminicpm_v_demo.so"
"$strip_bin" --strip-unneeded "$destination/libminicpm_v_demo.so"
