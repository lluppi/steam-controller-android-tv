cmake_minimum_required(VERSION 3.22.1)
project(uinput_jni)

# uinput_jni.cpp is the JNI bridge (named for the Kotlin class it serves); the two
# backends implement output_backend.h and are selected at runtime.
add_library(uinput_jni SHARED
    uinput_jni.cpp
    uinput_backend.cpp
    uhid_backend.cpp)

find_library(log-lib log)
target_link_libraries(uinput_jni ${log-lib})

target_compile_options(uinput_jni PRIVATE -Wall -Wextra -fvisibility=hidden)
