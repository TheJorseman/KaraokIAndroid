cmake_minimum_required(VERSION 3.22.1)
project(karaokei_whisper LANGUAGES C CXX)

# whisper.cpp is vendored at <repo>/third_party/whisper.cpp and pinned
# by the repository checkout. Keep the vendored source outside this
# Android module so it can be updated independently and reviewed.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# The whisper encoder is FP32 GEMM-bound. On x86_64 the optimized
# llamafile SGEMM works, but the ARM64 build has produced corrupted
# encoder output on real devices (uniform hallucinations from correct
# model + audio), so ARM64 uses the conservative vanilla ggml config:
# no llamafile, no OpenMP, baseline NEON. See the whisper quality
# tests before re-enabling any of these.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
else()
    set(GGML_OPENMP ON CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
endif()

# Android 15+ requires native libraries to be aligned to 16 KB pages.
# The flag only makes sense at link time, not when the static
# archives (libggml-base.a, libggml.a) are being created by llvm-ar.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

set(WHISPER_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/whisper.cpp")
if(NOT EXISTS "${WHISPER_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR "Missing vendored whisper.cpp at ${WHISPER_ROOT}")
endif()

# ARM64 Android defaults to armv8-a (NEON only). The armv8.2-a
# dot-product extension speeds up Q5_1 GEMM, but it is a suspect in
# corrupted encoder output on devices; keep the baseline NEON kernels
# until the quality tests pass with dotprod re-enabled.
# (dotprod intentionally not set for arm64-v8a)

add_subdirectory("${WHISPER_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp" EXCLUDE_FROM_ALL)

add_library(karaokei_whisper SHARED
    whisper_jni.cpp
)

target_compile_options(karaokei_whisper PRIVATE
    -Wall -Wextra -Wno-unused-parameter
    -fvisibility=hidden
)

# Android 15+ requires all native libraries to be aligned to 16 KB
# page boundaries (`-Wl,-z,max-page-size=16384`). Without this, the
# app fails the install-time alignment check and SystemUI hangs on
# the resulting "Not 16 KB compatible" alert.
target_link_options(karaokei_whisper PRIVATE
    -Wl,-z,max-page-size=16384
)

# JNI headers come from the NDK toolchain.
find_library(log-lib log)

target_link_libraries(karaokei_whisper PRIVATE ${log-lib})
target_link_libraries(karaokei_whisper PRIVATE whisper)
target_include_directories(karaokei_whisper PRIVATE "${WHISPER_ROOT}/include")
