package com.bd.tts.audiobook

/**
 * 听书行数相关的原生实现（C + JNI，见 jni/tts_lines.c）。
 *
 * 全部基于 ParcelFileDescriptor 的 raw fd，内部 dup + memchr 线性扫描：
 * 不建索引、不把正文读进 JVM，几百 MB 的文本也只在添加后数一次。
 *
 * 注意：库由 NativeEngine 首次加载，这里再 load 一次是幂等的（同一 classloader）。
 */
object TtsNative {
    init {
        System.loadLibrary("midu_bridge")
    }

    /**
     * 统计物理行数：以 \n 分隔，末尾无换行的非空内容也算一行；空文件 0 行。
     * 返回 -1 表示读取失败（fd 失效等）。
     */
    external fun countLines(fd: Int): Int

    /** 第 line 行（0 基）的行首字节偏移；越界 / 失败返回 -1，line<=0 恒为 0。 */
    external fun lineToOffset(fd: Int, line: Int): Long

    /** 字节偏移所在行的 0 基行号 = [0, offset) 内的换行数；失败返回 -1。 */
    external fun offsetToLine(fd: Int, offset: Long): Int
}
