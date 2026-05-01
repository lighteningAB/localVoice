cmake_minimum_required(VERSION 3.22.1)
project(localvoice_native LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Bump arm64 baseline so ggml compiles with DotProduct + FP16 SIMD intrinsics.
# armv8.2-a+dotprod+fp16 is supported by Snapdragon 845+ (all Nothing phones).
# Without this, ggml's quantized matmul falls back to scalar/baseline NEON,
# which is 2-4x slower for Q4/Q5/Q8 models.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8.2-a+dotprod+fp16)
endif()

# Strip whisper.cpp's extras and disable host-arch detection so cross-compilation
# for arm64-v8a is clean.
set(BUILD_SHARED_LIBS         OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER      OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2              OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE               OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP               OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE            OFF CACHE BOOL "" FORCE)
set(GGML_BLAS                 OFF CACHE BOOL "" FORCE)
set(GGML_CUDA                 OFF CACHE BOOL "" FORCE)
set(GGML_METAL                OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN               OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG        v1.7.4
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper log android)
