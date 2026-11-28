cmake_minimum_required(VERSION 3.22.1)
project(uoai_whisper_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Build whisper.cpp and ggml as static libraries folded into a single libwhisper_jni.so
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_STATIC ON CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

# Unlock ARMv8.2-A FP16 vector arithmetic + Int8 SDOT dotprod kernels on arm64-v8a (~2.5x-4x speedup)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -DNDEBUG")

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_COREML OFF CACHE BOOL "" FORCE)
set(WHISPER_OPENVINO OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)

# Enforce 16 KB ELF LOAD segment alignment for Android 15+ / Google Play compliance
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,--gc-sections")

add_subdirectory(whisper.cpp)

add_library(whisper_jni SHARED
    whisper_jni.cpp
)

target_compile_options(whisper_jni PRIVATE
    -O3
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
)

target_link_options(whisper_jni PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,--gc-sections
)

find_library(log-lib log)

target_link_libraries(whisper_jni PRIVATE
    whisper
    ggml
    ${log-lib}
)

# Cactus 4.0.0 native speech engine (Apache-2.0) for AksharSTT v4, AksharSTT Lite v4, and Shruti.
# Built as an independent shared library (libcactus_jni.so) so libwhisper_jni.so remains untouched.
set(CACTUS_STATIC_LIB "${CMAKE_CURRENT_SOURCE_DIR}/cactus/${ANDROID_ABI}/libcactus.a")
if (EXISTS "${CACTUS_STATIC_LIB}")
    add_library(cactus_jni SHARED
        cactus_jni.cpp
    )

    target_include_directories(cactus_jni PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/cactus"
    )

    target_compile_options(cactus_jni PRIVATE
        -O3
        -ffunction-sections
        -fdata-sections
        -fvisibility=hidden
    )

    target_link_options(cactus_jni PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,--gc-sections
    )

    find_library(m-lib m)

    target_link_libraries(cactus_jni PRIVATE
        "${CACTUS_STATIC_LIB}"
        ${log-lib}
        ${m-lib}
    )
endif()


