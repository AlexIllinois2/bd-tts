cmake_minimum_required(VERSION 3.22.1)
project(midu_bridge C)

# midu_bridge.c : 真正的 JNI 层（ART 侧，使用 NDK 的 jni.h）
# fake_jni.c    : host2 那套「假 JNIEnv」模型（只吃 jni_local.h，绝不 include jni.h）
# 两个 TU 分开编译，避免两套 JNI 类型定义冲突。
add_library(midu_bridge SHARED
        midu_bridge.c
        fake_jni.c)

target_include_directories(midu_bridge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(midu_bridge dl log)
