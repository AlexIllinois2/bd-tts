/*
 * tts_lines.c —— 听书文件「行数 / 行号 ↔ 字节偏移」的高性能实现。
 *
 * 只吃 ParcelFileDescriptor 的 raw fd（SAF / FileProvider / 普通文件都适用）：
 * 内部 dup 一份独立读写位置，用大 buffer + memchr 线性扫描，绝不建任何索引、
 * 不把正文读进 JVM。数百 MB 的文本也就是几十毫秒级的顺序读。
 *
 * 行定义与 Kotlin 侧一致：以 '\n' (0x0A) 分隔；末尾无换行的非空内容也算一行；
 * 空文件 0 行。UTF-8 的续字节都 >= 0x80，不会与 0x0A 混淆，直接按字节数即可。
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TTS_SCAN_BUF (256 * 1024)

/* 统计 '\n' 数量；文件非空且末字节不是 '\n' 时补一行。失败返回 -1。 */
static jint tts_count_lines(int fd) {
    int d = dup(fd);
    if (d < 0) return -1;
    if (lseek(d, 0, SEEK_SET) == (off_t)-1) { close(d); return -1; }
    char* buf = (char*)malloc(TTS_SCAN_BUF);
    if (!buf) { close(d); return -1; }

    jint lines = 0;
    int saw_data = 0;
    int last_nl = 0;
    ssize_t n = 0;
    while ((n = read(d, buf, TTS_SCAN_BUF)) > 0) {
        saw_data = 1;
        const char* p = buf;
        const char* end = buf + n;
        while ((p = (const char*)memchr(p, '\n', (size_t)(end - p))) != NULL) {
            lines++;
            p++;
        }
        last_nl = (buf[n - 1] == '\n');
    }
    free(buf);
    close(d);
    if (n < 0) return -1;
    if (saw_data && !last_nl) lines++;
    return lines;
}

/* 第 line 行（0 基）的行首字节偏移；越界 / 失败返回 -1。line<=0 恒为 0。 */
static jlong tts_line_offset(int fd, jint line) {
    if (line <= 0) return 0;
    int d = dup(fd);
    if (d < 0) return -1;
    if (lseek(d, 0, SEEK_SET) == (off_t)-1) { close(d); return -1; }
    char* buf = (char*)malloc(TTS_SCAN_BUF);
    if (!buf) { close(d); return -1; }

    jint cur = 0;
    jlong pos = 0;      /* 已跨过的字节数 = 下一个行首 */
    jlong result = -1;
    ssize_t n = 0;
    while ((n = read(d, buf, TTS_SCAN_BUF)) > 0) {
        const char* p = buf;
        const char* end = buf + n;
        while (p < end) {
            const char* nl = (const char*)memchr(p, '\n', (size_t)(end - p));
            if (!nl) { p = end; break; }
            pos += (nl - p) + 1;
            cur++;
            /* 第 line 行的行首就是第 line 个 '\n' 之后。末行即使没有 '\n'，
               它的行首同样在倒数第二个 '\n' 之后被这里命中，无需额外分支。 */
            if (cur == line) { result = pos; goto done; }
            p = nl + 1;
        }
    }
done:
    free(buf);
    close(d);
    return result;
}

/* 字节偏移所在行的 0 基行号 = [0, offset) 内的换行数。失败返回 -1。 */
static jint tts_offset_line(int fd, jlong offset) {
    if (offset <= 0) return 0;
    int d = dup(fd);
    if (d < 0) return -1;
    if (lseek(d, 0, SEEK_SET) == (off_t)-1) { close(d); return -1; }
    char* buf = (char*)malloc(TTS_SCAN_BUF);
    if (!buf) { close(d); return -1; }

    jint lines = 0;
    jlong left = offset;
    ssize_t n = 0;
    while (left > 0) {
        size_t want = (left < (jlong)TTS_SCAN_BUF) ? (size_t)left : TTS_SCAN_BUF;
        n = read(d, buf, want);
        if (n <= 0) break;
        const char* p = buf;
        const char* end = buf + n;
        while ((p = (const char*)memchr(p, '\n', (size_t)(end - p))) != NULL) {
            lines++;
            p++;
        }
        left -= n;
    }
    free(buf);
    close(d);
    if (n < 0) return -1;
    return lines;
}

JNIEXPORT jint JNICALL
Java_com_bd_tts_audiobook_TtsNative_countLines(JNIEnv* env, jclass cls, jint fd) {
    (void)env; (void)cls;
    return tts_count_lines(fd);
}

JNIEXPORT jlong JNICALL
Java_com_bd_tts_audiobook_TtsNative_lineToOffset(JNIEnv* env, jclass cls, jint fd, jint line) {
    (void)env; (void)cls;
    return tts_line_offset(fd, line);
}

JNIEXPORT jint JNICALL
Java_com_bd_tts_audiobook_TtsNative_offsetToLine(JNIEnv* env, jclass cls, jint fd, jlong offset) {
    (void)env; (void)cls;
    return tts_offset_line(fd, offset);
}
