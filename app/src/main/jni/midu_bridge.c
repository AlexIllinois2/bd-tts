/*
 * midu_bridge.c —— 真正的 JNI 层（ART 侧）。
 * 只做四件事：字符串转换、String[]/int[] → C 数组、调 fake_jni.c 的纯 C 接口、
 * 把 PCM 变成 byte[]。引擎的回调线程永远不进 ART，不需要跨线程 JNI。
 *
 * 注意：这里用 JNI 静态命名，函数名必须与 NativeEngine.java 的 native 方法名逐字一致
 * （Java_com_bd_tts_engine_NativeEngine_<方法名>）。曾经误写成 *_nativeInit 等，
 * 导致实机报 "No implementation found for ... NativeEngine.init"。
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <android/log.h>

#include "fake_jni.h"

#define LOGTAG "midu-bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOGTAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOGTAG, __VA_ARGS__)

static JavaVM* g_vm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

/* jstring -> 真正的 UTF-8（不用 modified UTF-8，避免 emoji/补充平面被拆成代理对）。 */
static char* jstr_to_utf8(JNIEnv* env, jstring s) {
    if (!s) return strdup("");
    jsize n = (*env)->GetStringLength(env, s);
    const jchar* u = (*env)->GetStringChars(env, s, NULL);
    if (!u) return strdup("");
    char* out = (char*)malloc((size_t)n * 3 + 4);
    size_t k = 0;
    for (jsize i = 0; i < n; i++) {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n &&
            u[i+1] >= 0xDC00 && u[i+1] <= 0xDFFF) {
            cp = 0x10000u + ((cp - 0xD800u) << 10) + (uint32_t)(u[i+1] - 0xDC00u);
            i++;
        }
        if (cp < 0x80) {
            out[k++] = (char)cp;
        } else if (cp < 0x800) {
            out[k++] = (char)(0xC0 | (cp >> 6));
            out[k++] = (char)(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out[k++] = (char)(0xE0 | (cp >> 12));
            out[k++] = (char)(0x80 | ((cp >> 6) & 0x3F));
            out[k++] = (char)(0x80 | (cp & 0x3F));
        } else {
            out[k++] = (char)(0xF0 | (cp >> 18));
            out[k++] = (char)(0x80 | ((cp >> 12) & 0x3F));
            out[k++] = (char)(0x80 | ((cp >> 6) & 0x3F));
            out[k++] = (char)(0x80 | (cp & 0x3F));
        }
    }
    out[k] = 0;
    (*env)->ReleaseStringChars(env, s, u);
    return out;
}

/* jobjectArray(String) -> char**；返回堆上数组，需用 free_str_array 释放。 */
static char** jstr_array(JNIEnv* env, jobjectArray arr, jsize* outN) {
    if (!arr) { *outN = 0; return NULL; }
    jsize n = (*env)->GetArrayLength(env, arr);
    char** out = (char**)calloc((size_t)(n > 0 ? n : 1), sizeof(char*));
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        out[i] = jstr_to_utf8(env, s);
        if (s) (*env)->DeleteLocalRef(env, s);
    }
    *outN = n;
    return out;
}
static void free_str_array(char** a, jsize n) {
    if (!a) return;
    for (jsize i = 0; i < n; i++) free(a[i]);
    free(a);
}

static void throw_ise(JNIEnv* env, const char* msg) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls) (*env)->ThrowNew(env, cls, msg ? msg : "midu engine error");
}

/* 合成 + PCM -> byte[] 的公共实现。 */
static jbyteArray do_synth(JNIEnv* env, jlong handle, const char* text,
                           int speed, int pitch, int volume, int timeoutMs,
                           char** keys, char** vals, const int* types, int n) {
    if (!handle || !text) return NULL;
    int r = midu_engine_synth_ex((void*)(intptr_t)handle, text,
                                 speed, pitch, volume, timeoutMs,
                                 (const char* const*)keys,
                                 (const char* const*)vals, types, n);
    if (r <= 0) {
        LOGE("synth 返回 %d：%s", r, midu_engine_last_error());
        return NULL;
    }
    int len = 0;
    const uint8_t* pcm = midu_engine_pcm((void*)(intptr_t)handle, &len);
    if (!pcm || len <= 0) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
    if (!arr) return NULL;
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte*)pcm);
    return arr;
}

JNIEXPORT jlong JNICALL
Java_com_bd_tts_engine_NativeEngine_init(JNIEnv* env, jclass cls,
        jstring jLibDir, jstring jRoot, jstring jVoice, jstring jBase, jint sampleRate) {
    (void)cls;
    char *libDir = jstr_to_utf8(env, jLibDir);
    char *root   = jstr_to_utf8(env, jRoot);
    char *voice  = jstr_to_utf8(env, jVoice);
    char *base   = jstr_to_utf8(env, jBase);
    char err[1024] = {0};
    void* h = midu_engine_load(libDir, root, voice, base, (int)sampleRate, err, (int)sizeof(err));
    free(libDir); free(root); free(voice); free(base);
    if (!h) {
        LOGE("init 失败：%s", err);
        throw_ise(env, err[0] ? err : "midu_engine_load failed");
        return 0;
    }
    LOGI("init ok handle=%p", h);
    return (jlong)(intptr_t)h;
}

/** 固定三参数版：保持旧调用点兼容。 */
JNIEXPORT jbyteArray JNICALL
Java_com_bd_tts_engine_NativeEngine_synth(JNIEnv* env, jclass cls,
        jlong handle, jstring jText, jint speed, jint pitch, jint volume, jint timeoutMs) {
    (void)cls;
    char* text = jstr_to_utf8(env, jText);
    jbyteArray arr = do_synth(env, handle, text, (int)speed, (int)pitch,
                              (int)volume, (int)timeoutMs, NULL, NULL, NULL, 0);
    free(text);
    return arr;
}

/** 通用版：额外接受 keys / values / types 三条平行数组，下发任意 tts_* 选项。 */
JNIEXPORT jbyteArray JNICALL
Java_com_bd_tts_engine_NativeEngine_synthEx(JNIEnv* env, jclass cls,
        jlong handle, jstring jText, jint speed, jint pitch, jint volume, jint timeoutMs,
        jobjectArray jKeys, jobjectArray jVals, jintArray jTypes) {
    (void)cls;
    char* text = jstr_to_utf8(env, jText);
    jsize nk = 0, nv = 0, nt = 0;
    char** keys = jstr_array(env, jKeys, &nk);
    char** vals = jstr_array(env, jVals, &nv);
    jint* types = NULL;
    if (jTypes) {
        nt = (*env)->GetArrayLength(env, jTypes);
        types = (*env)->GetIntArrayElements(env, jTypes, NULL);
    }
    int n = (int)(nk < nv ? nk : nv);
    if (n > nt) n = (int)nt;
    if (n < 0) n = 0;

    jbyteArray arr = do_synth(env, handle, text, (int)speed, (int)pitch,
                              (int)volume, (int)timeoutMs,
                              keys, vals, (const int*)types, n);

    if (jTypes && types) (*env)->ReleaseIntArrayElements(env, jTypes, types, JNI_ABORT);
    free_str_array(vals, nv);
    free_str_array(keys, nk);
    free(text);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_bd_tts_engine_NativeEngine_stop(JNIEnv* env, jclass cls, jlong handle) {
    (void)env;(void)cls;
    midu_engine_stop((void*)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_com_bd_tts_engine_NativeEngine_destroy(JNIEnv* env, jclass cls, jlong handle) {
    (void)env;(void)cls;
    midu_engine_destroy((void*)(intptr_t)handle);
}

JNIEXPORT jstring JNICALL
Java_com_bd_tts_engine_NativeEngine_lastError(JNIEnv* env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, midu_engine_last_error());
}

JNIEXPORT jstring JNICALL
Java_com_bd_tts_engine_NativeEngine_version(JNIEnv* env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, midu_engine_version());
}
