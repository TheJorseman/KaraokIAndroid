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

# Android 15+ requires native libraries to be aligned to 16 KB pages.
# The flag only makes sense at link time, not when the static
# archives (libggml-base.a, libggml.a) are being created by llvm-ar.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

set(WHISPER_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/whisper.cpp")
if(NOT EXISTS "${WHISPER_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR "Missing vendored whisper.cpp at ${WHISPER_ROOT}")
endif()

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
