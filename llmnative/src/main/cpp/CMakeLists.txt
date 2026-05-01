cmake_minimum_required(VERSION 3.22.1)
project(llmnative LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Match the SIMD baseline used in the whisper module so ggml's quantized matmul
# kernels use SDOT and FP16 fast paths (Snapdragon 845+).
if (ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8.2-a+dotprod+fp16)
endif()

# Strip llama.cpp extras and disable host-arch detection.
set(BUILD_SHARED_LIBS         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS         OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL                OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE               OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP               OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE            OFF CACHE BOOL "" FORCE)
set(GGML_BLAS                 OFF CACHE BOOL "" FORCE)
set(GGML_CUDA                 OFF CACHE BOOL "" FORCE)
set(GGML_METAL                OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN               OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b5450
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(llm_jni SHARED llm_jni.cpp)
target_link_libraries(llm_jni PRIVATE llama log android)
