# ==== 1. Boiler-plate ====
cmake_minimum_required(VERSION 3.22)  # AGP 8.x ships 3.22+
project(whisper_android LANGUAGES C CXX)

# ==== 2. Global compile options ====
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Neon / fp16 for modern 64-bit devices
if (ANDROID)
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -march=armv8.2-a+fp16")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -march=armv8.2-a+fp16")
endif()

# ==== 3. Configure the *upstream* project ====
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp)

# ==== 4. Your JNI bridge (one .cpp file for now) ====
add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp
)

# ==== 5. Linkage ====
target_link_libraries(whisper_jni
        whisper
        android log
)
