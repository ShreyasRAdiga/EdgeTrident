cmake_minimum_required(VERSION 3.22.1)

project(litevisionengine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(CMAKE_ANDROID_ARCH_ABI)
    set(LITEVISION_ANDROID_ABI "${CMAKE_ANDROID_ARCH_ABI}")
else()
    set(LITEVISION_ANDROID_ABI "${ANDROID_ABI}")
endif()

set(LITEVISION_NCNN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${LITEVISION_ANDROID_ABI}/lib/cmake/ncnn")
set(LITEVISION_HAS_NCNN OFF)

if(EXISTS "${LITEVISION_NCNN_DIR}/ncnnConfig.cmake" OR EXISTS "${LITEVISION_NCNN_DIR}/ncnn-config.cmake")
    set(ncnn_DIR "${LITEVISION_NCNN_DIR}" CACHE PATH "NCNN Android package config")
    find_package(ncnn REQUIRED CONFIG)
    set(LITEVISION_HAS_NCNN ON)
else()
    message(STATUS "NCNN package config was not found at ${LITEVISION_NCNN_DIR}. Building LiteVisionEngine with a JNI fallback; extract the NCNN Android package there for real tracking.")
endif()

add_library(litevisionengine SHARED
    litevisionengine_jni.cpp
)

find_library(log-lib log)

target_link_libraries(litevisionengine
    PRIVATE
        android
        ${log-lib}
)

if(LITEVISION_HAS_NCNN)
    target_compile_definitions(litevisionengine PRIVATE LITEVISIONENGINE_HAS_NCNN=1)
    target_link_libraries(litevisionengine PRIVATE ncnn)
else()
    target_compile_definitions(litevisionengine PRIVATE LITEVISIONENGINE_HAS_NCNN=0)
endif()

target_compile_options(litevisionengine
    PRIVATE
        -Wall
        -Wextra
)
