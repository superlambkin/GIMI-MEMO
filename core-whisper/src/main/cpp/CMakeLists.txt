cmake_minimum_required(VERSION 3.18)
project("whisper")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# Disable GGML shared libraries - link statically into whisper.so
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD OFF CACHE BOOL "" FORCE)
set(GGML_SHARED OFF CACHE BOOL "" FORCE)

# Source files: whisper.cpp library + JNI wrapper
set(SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.c
)

# Global include directories (applies to all targets including FetchContent)
include_directories(
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
)

find_library(LOG_LIB log)

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

# ─── OpenCL 対応 (v0.8.x reserved) ────────────────────────────────────────
# ヘッダーとライブラリの存在は確認済みだが、カーネル埋め込みに Python ビルド
# ステップが必要なため、本リリースでは OpenCL を無効化。
# 将来: opencl-headers/ + ggml-opencl.cpp + Python kernel embed で再有効化。
set(HAVE_OPENCL FALSE)

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        ${SOURCE_FILES}
    )

    target_link_libraries(${target_name} ${LOG_LIB} android ggml)
    if (HAVE_OPENCL)
        target_link_libraries(${target_name} PRIVATE ggml-opencl ${OpenCL_LIBRARY})
        target_include_directories(${target_name} PRIVATE ${OPENCL_INCLUDE_DIR})
        target_compile_definitions(${target_name} PUBLIC GGML_USE_OPENCL)
    else()
        target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    endif()
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="1.7.4")

    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O3)
        target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
        target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)
    endif ()
endfunction()

build_library("whisper")

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
    target_compile_options(whisper_v8fp16_va PRIVATE -march=armv8.2-a+fp16)
endif ()
