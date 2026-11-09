/*
 * fake_jni.c —— 把 host2rig 里 proven 的「假 JNIEnv 宿主」改造成可重入的库。
 *
 * 来源：src/host2.c (§ 假 Java 对象模型 / 通用桩 / JNI 函数指针 / 选项串)。
 * 差异：
 *   1) 没有 main()，改成 midu_engine_load / synth / pcm / destroy 四个可重入入口；
 *   2) 假 JavaVM 改成 static（引擎会缓存 JavaVM 指针，不能放栈上）；
 *   3) PCM 不再写文件，而是攒在 handle 里，由 JNI 层一次性取走；
 *   4) 依赖库用绝对路径 dlopen(RTLD_GLOBAL)，顺序模拟 rig 的 LD_PRELOAD；
 *   5) 不 include NDK 的 jni.h（类型定义会打架），只吃 jni_local.h。
 *
 * 合规：不修改 libspeechengine.so 一个字节，不伪造授权判决；喂的是用户设备上
 *       合法持有的 speech_license.licbag 原件，宿主身份按 rig 原样回报。
 */
#include "jni_local.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdarg.h>
#include <ctype.h>
#include <dlfcn.h>
#include <pthread.h>
#include <time.h>
#include <errno.h>
#include <unistd.h>
#include <android/log.h>

#include "fake_jni.h"

#define LOGTAG "midu-fakejni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOGTAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOGTAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOGTAG, __VA_ARGS__)

/* -------------------------------------------------------------------------
 * 假 Java 对象模型（与 host2.c 逐字同构）
 * ---------------------------------------------------------------------- */
typedef struct FakeObj {
    int    type;   /* 0=string 1=byte[] 2=object(callback) */
    void  *data;
    size_t len;
    void  *iobj;
} FakeObj;

static jobject new_str(const char *s) {
    FakeObj *o = (FakeObj*)calloc(1, sizeof(*o));
    o->type = 0;
    o->data = strdup(s ? s : "");
    o->len  = strlen((char*)o->data);
    return (jobject)o;
}
static jobject new_bytes(const void *p, size_t n) {
    FakeObj *o = (FakeObj*)calloc(1, sizeof(*o));
    o->type = 1;
    o->data = malloc(n ? n : 1);
    if (p && n) memcpy(o->data, p, n);
    o->len = n;
    return (jobject)o;
}
static jobject new_obj(void) {
    FakeObj *o = (FakeObj*)calloc(1, sizeof(*o));
    o->type = 2;
    return (jobject)o;
}
static FakeObj* asObj(jobject o) { return (FakeObj*)o; }

/* -------------------------------------------------------------------------
 * handle / PCM 收集
 * ---------------------------------------------------------------------- */
typedef jlong   (*fp_createEngine)(JNIEnv*, jclass, jobject);
typedef jint    (*fp_initEngine)(JNIEnv*, jclass, jlong, jobject);
typedef jint    (*fp_sendDirective)(JNIEnv*, jclass, jlong, jint, jstring);
typedef void    (*fp_setOptStr)(JNIEnv*, jclass, jlong, jstring, jstring);
typedef void    (*fp_setOptInt)(JNIEnv*, jclass, jlong, jstring, jint);
typedef void    (*fp_setOptBool)(JNIEnv*, jclass, jlong, jstring, jboolean);
typedef void    (*fp_setOptDbl)(JNIEnv*, jclass, jlong, jstring, jdouble);
typedef void    (*fp_destroy)(JNIEnv*, jclass, jlong);
typedef jstring (*fp_getVersion)(JNIEnv*, jclass);

typedef struct MiduHandle {
    void* h_engine;
    fp_createEngine   fn_ce;
    fp_initEngine     fn_ie;
    fp_sendDirective  fn_sd;
    fp_setOptStr      fn_ss;
    fp_setOptInt      fn_si;
    fp_setOptBool     fn_sb;
    fp_setOptDbl      fn_sdbl;
    fp_destroy        fn_de;
    fp_getVersion     fn_gv;

    jlong  engine;
    JNIEnv* penv;      /* 指向 g_callback_env_holder，双指针语义 */
    jclass clazz;
    jobject callback;

    uint8_t* pcm;
    size_t   pcm_len;
    size_t   pcm_cap;
    int      end_marker;
    int      stopped;
    pthread_mutex_t lock;
    pthread_cond_t  cond;

    char root[768];
    char voice[128];
    char base[64];
} MiduHandle;

static MiduHandle* g_current = NULL;     /* 当前正在收 PCM 的 handle */
static char g_last_error[1024] = {0};

static void set_error(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_last_error, sizeof(g_last_error), fmt, ap);
    va_end(ap);
    LOGE("%s", g_last_error);
}
const char* midu_engine_last_error(void) { return g_last_error; }
const char* midu_engine_version(void) { return "midu-fakejni/1.0 (host2 rig parity)"; }

static void capture(jbyteArray arr) {
    FakeObj *o = asObj(arr);
    MiduHandle *h = g_current;
    if (!o || !o->data || !o->len || !h) return;
    pthread_mutex_lock(&h->lock);
    if (h->pcm_len + o->len > h->pcm_cap) {
        size_t cap = h->pcm_cap ? h->pcm_cap : (64u << 10);
        while (cap < h->pcm_len + o->len) cap <<= 1;
        if (cap > (64u << 20)) {           /* 64 MB 硬上限，防失控 */
            pthread_mutex_unlock(&h->lock);
            set_error("PCM 超过 64MB 上限，已丢弃");
            return;
        }
        uint8_t *p = (uint8_t*)realloc(h->pcm, cap);
        if (!p) {
            pthread_mutex_unlock(&h->lock);
            set_error("PCM 缓冲区 realloc 失败");
            return;
        }
        h->pcm = p;
        h->pcm_cap = cap;
    }
    memcpy(h->pcm + h->pcm_len, o->data, o->len);
    h->pcm_len += o->len;
    pthread_cond_broadcast(&h->cond);
    pthread_mutex_unlock(&h->lock);
}

static void mark_end(MiduHandle *h) {
    if (!h) return;
    pthread_mutex_lock(&h->lock);
    h->end_marker = 1;
    pthread_cond_broadcast(&h->cond);
    pthread_mutex_unlock(&h->lock);
}

#include "jnienv_stub.h"    /* init_jni_stubs()：把未实现槽位填满可诊断的桩 */

/* -------------------------------------------------------------------------
 * 假 JNIEnv 函数表（只实现引擎真正会调的那几枚）
 * ---------------------------------------------------------------------- */
typedef struct FakeID { char name[64]; char sig[128]; void* data; } FakeID;

static jint my_GetVersion(JNIEnv *e) { (void)e; return JNI_VERSION_1_6; }
static const char* my_GetStringUTFChars(JNIEnv *e, jstring s, jboolean *isCopy) {
    (void)e; if (isCopy) *isCopy = JNI_FALSE;
    FakeObj *o = asObj(s);
    return o ? (char*)o->data : "";
}
static void my_ReleaseStringUTFChars(JNIEnv *e, jstring s, const char *c) { (void)e;(void)s;(void)c; }
static jsize my_GetStringUTFLength(JNIEnv *e, jstring s) { (void)e; FakeObj *o=asObj(s); return o?(jsize)o->len:0; }
static jsize my_GetStringLength(JNIEnv *e, jstring s) { (void)e; FakeObj *o=asObj(s); return o?(jsize)o->len:0; }
static jstring my_NewStringUTF(JNIEnv *e, const char *utf) { (void)e; return (jstring)new_str(utf?utf:""); }
static jsize my_GetArrayLength(JNIEnv *e, jarray a) { (void)e; FakeObj *o=asObj(a); return o?(jsize)o->len:0; }
static jbyteArray my_NewByteArray(JNIEnv *e, jsize n) { (void)e; return (jbyteArray)new_bytes(NULL,(size_t)(n>0?n:0)); }
static void my_GetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize s, jsize l, jbyte *b) {
    (void)e; FakeObj *o=asObj(a); if (o && o->data) memcpy(b, (char*)o->data+s, (size_t)l);
}
static void my_SetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize s, jsize l, const jbyte *b) {
    (void)e; FakeObj *o=asObj(a);
    if (o && o->data && o->len >= (size_t)(s+l)) memcpy((char*)o->data+s, b, (size_t)l);
}
static jbyte* my_GetByteArrayElements(JNIEnv *e, jbyteArray a, jboolean *isCopy) {
    (void)e; if (isCopy) *isCopy = JNI_FALSE;
    FakeObj *o = asObj(a);
    return o ? (jbyte*)o->data : NULL;
}
static void my_ReleaseByteArrayElements(JNIEnv *e, jbyteArray a, jbyte *elems, jint mode) {
    (void)e;(void)a;(void)elems;(void)mode;
}
static void* my_GetPrimitiveArrayCritical(JNIEnv *e, jarray a, jboolean *isCopy) {
    (void)e; if (isCopy) *isCopy = JNI_FALSE;
    FakeObj *o = asObj(a);
    return o ? o->data : NULL;
}
static void my_ReleasePrimitiveArrayCritical(JNIEnv *e, jarray a, void *c, jint mode) {
    (void)e;(void)a;(void)c;(void)mode;
}
static jobject my_NewGlobalRef(JNIEnv *e, jobject o) { (void)e; return o; }
static void my_DeleteGlobalRef(JNIEnv *e, jobject o) { (void)e;(void)o; }
static void my_DeleteLocalRef(JNIEnv *e, jobject o) { (void)e;(void)o; }
static jobject my_GetObjectClass(JNIEnv *e, jobject o) { (void)e;(void)o; return (jclass)new_obj(); }
static jboolean my_IsSameObject(JNIEnv *e, jobject a, jobject b) { (void)e; return (a==b)?JNI_TRUE:JNI_FALSE; }
static jboolean my_ExceptionCheck(JNIEnv *e) { (void)e; return JNI_FALSE; }

static jmethodID my_GetMethodID(JNIEnv *e, jclass c, const char *name, const char *sig) {
    (void)e;(void)c;
    FakeID *m = (FakeID*)calloc(1, sizeof(*m));
    strncpy(m->name, name ? name : "", 63);
    strncpy(m->sig,  sig  ? sig  : "", 127);
    return (jmethodID)m;
}

static int is_speech_msg(FakeID *m) { return m && strcmp(m->name, "onSpeechMessage") == 0; }

static void pcm_from_varg(va_list ap, MiduHandle *h) {
    va_list ap2; va_copy(ap2, ap);
    jint i1 = va_arg(ap2, jint);
    jbyteArray b = va_arg(ap2, jbyteArray);
    jint i2 = va_arg(ap2, jint);
    va_end(ap2);
    if (i1 == 1400) capture(b);                 /* TTS_AUDIO_DATA */
    else if (i1 == 1409) mark_end(h);           /* TTS_AUDIO_DATA_END */
    else if (i1 >= 2000) LOGW("onSpeechMessage 异常码 %d (%d)", (int)i1, (int)i2);
    (void)i2;
}
static void pcm_from_jval(const jvalue *a, MiduHandle *h) {
    (void)h;
    if (a) capture((jbyteArray)a[1].l);
}

/* 引擎回调线程拿到的就是这套假 env；它永远不需要真的 ART。 */
static jint my_CallIntMethodV(JNIEnv *e, jobject o, jmethodID mid, va_list ap) {
    (void)e;(void)o;
    if (is_speech_msg((FakeID*)mid)) pcm_from_varg(ap, g_current);
    return 0;
}
static jint my_CallIntMethod(JNIEnv *e, jobject o, jmethodID mid, ...) {
    (void)e;(void)o; va_list ap; va_start(ap, mid);
    if (is_speech_msg((FakeID*)mid)) pcm_from_varg(ap, g_current);
    va_end(ap); return 0;
}
static jint my_CallIntMethodA(JNIEnv *e, jobject o, jmethodID mid, const jvalue *a) {
    (void)e;(void)o;
    if (is_speech_msg((FakeID*)mid)) pcm_from_jval(a, g_current);
    return 0;
}
static void my_CallVoidMethodV(JNIEnv *e, jobject o, jmethodID mid, va_list ap) {
    (void)e;(void)o;
    if (is_speech_msg((FakeID*)mid)) pcm_from_varg(ap, g_current);
}
static void my_CallVoidMethod(JNIEnv *e, jobject o, jmethodID mid, ...) {
    (void)e;(void)o; va_list ap; va_start(ap, mid);
    if (is_speech_msg((FakeID*)mid)) pcm_from_varg(ap, g_current);
    va_end(ap);
}
static void my_CallVoidMethodA(JNIEnv *e, jobject o, jmethodID mid, const jvalue *a) {
    (void)e;(void)o;
    if (is_speech_msg((FakeID*)mid)) pcm_from_jval(a, g_current);
}
/* licbag 绑定的是宿主包名：按 rig 原样回报，喂的是合法凭证原件。 */
static const char* HOST_PACKAGE = "com.lechuan.mdwz";
static int is_host_identity(FakeID *m) {
    return m && (strcmp(m->name,"getPackageName")==0 ||
                 strcmp(m->name,"toString")==0 ||
                 strcmp(m->name,"getApplication")==0);
}
static jobject my_CallObjectMethodV(JNIEnv *e, jobject o, jmethodID mid, va_list ap) {
    (void)e;(void)o;(void)ap;
    return is_host_identity((FakeID*)mid) ? (jobject)new_str(HOST_PACKAGE) : (jobject)new_obj();
}
static jobject my_CallObjectMethod(JNIEnv *e, jobject o, jmethodID mid, ...) {
    (void)e;(void)o;
    return is_host_identity((FakeID*)mid) ? (jobject)new_str(HOST_PACKAGE) : (jobject)new_obj();
}
static jobject my_CallObjectMethodA(JNIEnv *e, jobject o, jmethodID mid, const jvalue *a) {
    (void)e;(void)o;(void)a;
    return is_host_identity((FakeID*)mid) ? (jobject)new_str(HOST_PACKAGE) : (jobject)new_obj();
}

static struct JNINativeInterface g_fake_table;
static struct { const struct JNINativeInterface* functions; } g_callback_env_holder;

static void init_fake_env(void) {
    memset(&g_fake_table, 0, sizeof(g_fake_table));
    init_jni_stubs(&g_fake_table);
    g_fake_table.GetVersion                 = my_GetVersion;
    g_fake_table.GetStringUTFChars          = my_GetStringUTFChars;
    g_fake_table.ReleaseStringUTFChars      = my_ReleaseStringUTFChars;
    g_fake_table.GetStringUTFLength         = my_GetStringUTFLength;
    g_fake_table.GetStringLength            = my_GetStringLength;
    g_fake_table.NewStringUTF               = my_NewStringUTF;
    g_fake_table.GetArrayLength             = my_GetArrayLength;
    g_fake_table.NewByteArray               = my_NewByteArray;
    g_fake_table.GetByteArrayRegion         = my_GetByteArrayRegion;
    g_fake_table.SetByteArrayRegion         = my_SetByteArrayRegion;
    g_fake_table.GetByteArrayElements       = my_GetByteArrayElements;
    g_fake_table.ReleaseByteArrayElements   = my_ReleaseByteArrayElements;
    g_fake_table.GetPrimitiveArrayCritical  = my_GetPrimitiveArrayCritical;
    g_fake_table.ReleasePrimitiveArrayCritical = my_ReleasePrimitiveArrayCritical;
    g_fake_table.NewGlobalRef               = my_NewGlobalRef;
    g_fake_table.DeleteGlobalRef            = my_DeleteGlobalRef;
    g_fake_table.DeleteLocalRef             = my_DeleteLocalRef;
    g_fake_table.GetObjectClass             = my_GetObjectClass;
    g_fake_table.IsSameObject               = my_IsSameObject;
    g_fake_table.ExceptionCheck             = my_ExceptionCheck;
    g_fake_table.GetMethodID                = my_GetMethodID;
    g_fake_table.CallIntMethod              = my_CallIntMethod;
    g_fake_table.CallIntMethodV             = my_CallIntMethodV;
    g_fake_table.CallIntMethodA             = my_CallIntMethodA;
    g_fake_table.CallVoidMethod             = my_CallVoidMethod;
    g_fake_table.CallVoidMethodV            = my_CallVoidMethodV;
    g_fake_table.CallVoidMethodA            = my_CallVoidMethodA;
    g_fake_table.CallObjectMethod           = my_CallObjectMethod;
    g_fake_table.CallObjectMethodV          = my_CallObjectMethodV;
    g_fake_table.CallObjectMethodA          = my_CallObjectMethodA;
    g_callback_env_holder.functions = &g_fake_table;
}

/* 假 JavaVM：引擎回调线程 GetEnv/AttachCurrentThread 走这里（必须 static！） */
static jint fake_GetEnv(void *vm, void **env, jint version) {
    (void)vm;(void)version; *env = (void*)&g_callback_env_holder; return JNI_OK;
}
static jint fake_AttachCurrentThread(void *vm, void **env, void *args) {
    (void)vm;(void)args; *env = (void*)&g_callback_env_holder; return JNI_OK;
}
static jint fake_DetachCurrentThread(void *vm) { (void)vm; return JNI_OK; }
static jint fake_DestroyJavaVM(void *vm) { (void)vm; return JNI_OK; }

static struct JNIInvokeInterface g_invoke = {0};
static struct _JavaVM g_jvm = {0};

static void init_fake_vm(void) {
    memset(&g_invoke, 0, sizeof(g_invoke));
    g_invoke.DestroyJavaVM        = (void*)fake_DestroyJavaVM;
    g_invoke.AttachCurrentThread  = (void*)fake_AttachCurrentThread;
    g_invoke.DetachCurrentThread  = (void*)fake_DetachCurrentThread;
    g_invoke.GetEnv               = (void*)fake_GetEnv;
    memset(&g_jvm, 0, sizeof(g_jvm));
    g_jvm.functions = &g_invoke;
}

/* -------------------------------------------------------------------------
 * 引擎选项（与 host2.c 的 setStr/setInt/setBool 同构）
 * ---------------------------------------------------------------------- */
static void setStr(MiduHandle *h, const char *k, const char *v) {
    if (!h->fn_ss) return;
    jobject jk = new_str(k), jv = new_str(v ? v : "");
    h->fn_ss(h->penv, h->clazz, h->engine, (jstring)jk, (jstring)jv);
}
static void setInt(MiduHandle *h, const char *k, int v) {
    if (!h->fn_si) return;
    h->fn_si(h->penv, h->clazz, h->engine, (jstring)new_str(k), (jint)v);
}
static void setBool(MiduHandle *h, const char *k, int v) {
    if (!h->fn_sb) return;
    h->fn_sb(h->penv, h->clazz, h->engine, (jstring)new_str(k), v ? JNI_TRUE : JNI_FALSE);
}
static void setDbl(MiduHandle *h, const char *k, double v) {
    /* 引擎未必导出 setOptionDoubleToNative；缺符号时静默跳过，不破坏主链路。 */
    if (!h->fn_sdbl) return;
    h->fn_sdbl(h->penv, h->clazz, h->engine, (jstring)new_str(k), (jdouble)v);
}

/* 这些是 rig 逐条验过、钉死的初始化档位。UI 暴露「任意 tts_* 选项」时，
 * 必须挡住它们：改了会直接破坏授权 / 资源定位 / PCM 回调（例如把
 * tts_data_callback_mode 改掉就再也收不到 1400/1409）。 */
static const char *RESERVED_KEYS[] = {
    "tts_text", "tts_work_mode", "engine_name", "authenticate_type",
    "license_directory", "settings_file_dir", "tracking_data_path",
    "tts_off_resource_path", "tts_voice_offline", "tts_voice_type_offline",
    "tts_data_callback_mode", "tts_enable_player", "prevent_player_creation",
    "tts_rate", "sample_rate", "custom_sample_rate",
    "tts_with_frontend", "tts_frontend_type", "tts_enable_progress",
    "tts_scenario", "tts_enable_dump"
};
static int is_reserved(const char *k) {
    for (size_t i = 0; i < sizeof(RESERVED_KEYS)/sizeof(RESERVED_KEYS[0]); i++)
        if (strcmp(k, RESERVED_KEYS[i]) == 0) return 1;
    return 0;
}

/* 逐条下发一条通用选项；返回 1 表示真的下发了。 */
static int apply_option(MiduHandle *h, const char *k, const char *v, int type) {
    if (!h || !k || !*k || !v) return 0;
    if (is_reserved(k)) {
        LOGW("选项 %s 属于钉死的初始化档位，已忽略（UI 不应下发）", k);
        return 0;
    }
    /* 只放行 tts_* / voiceconv_* / 少数前端覆写键，防止误把任意字符串喂给引擎。 */
    if (strncmp(k, "tts_", 4) != 0 && strncmp(k, "voiceconv_", 10) != 0) {
        LOGW("选项 %s 不在允许前缀内，已忽略", k);
        return 0;
    }
    LOGI("opt %s(type=%d) = %s", k, type, v);
    switch (type) {
        case MIDU_OPT_INT:    setInt (h, k, atoi(v)); break;
        case MIDU_OPT_BOOL:   setBool(h, k, v[0]=='1' || v[0]=='t' || v[0]=='T'); break;
        case MIDU_OPT_DOUBLE: setDbl (h, k, atof(v)); break;
        default:              setStr (h, k, v); break;
    }
    return 1;
}

static void* dlopen_at(const char *lib_dir, const char *name, int required) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s", lib_dir, name);
    void *h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        char *e = dlerror();
        if (required) set_error("dlopen 失败 %s : %s", path, e ? e : "?");
        else LOGW("可选库 %s 未加载：%s", name, e ? e : "?");
    }
    return h;
}

#define SYM(h, out, name) do { \
    out = (typeof(out))dlsym(h, "Java_com_bytedance_speech_speechengine_bridge_SpeechEngineBridge_" name); \
} while (0)

void* midu_engine_load(const char *lib_dir, const char *res_root,
                       const char *voice, const char *base,
                       int sample_rate, char *err, int err_cap) {
    g_last_error[0] = 0;
    if (!lib_dir || !res_root || !voice || !base) { set_error("参数为空"); goto fail; }

    init_fake_env();
    init_fake_vm();

    /* 1) shim 先入全局符号表（等价于 rig 的 LD_PRELOAD）。
     *    log_shim 在 app 进程里不需要：系统 liblog 是真的。 */
    dlopen_at(lib_dir, "icu_shim.so", 0);
    dlopen_at(lib_dir, "cronet_shim4.so", 0);

    /* 2) 依赖闭包：全部按绝对路径预置成 RTLD_GLOBAL。 */
    static const char *DEPS[] = {
        "libttcrypto.so", "libttboringssl.so", "libsscronet.so",
        "libicuuc.so", "libicui18n.so", "libc++_shared.so", "libspeexdsp.so"
        /* 不加载 log_shim.so：app 进程里系统 liblog 是真的，抢走 __android_log_print
         * 只会让 logcat 变空。rig 需要它只是因为那是无 ART 的命令行进程。 */
    };
    for (size_t i = 0; i < sizeof(DEPS)/sizeof(DEPS[0]); i++) dlopen_at(lib_dir, DEPS[i], 0);

    MiduHandle *h = (MiduHandle*)calloc(1, sizeof(MiduHandle));
    if (!h) { set_error("calloc handle 失败"); goto fail; }
    pthread_mutex_init(&h->lock, NULL);
    pthread_cond_init(&h->cond, NULL);

    h->h_engine = dlopen_at(lib_dir, "libspeechengine.so", 1);
    if (!h->h_engine) goto fail_handle;

    typedef jint (*fp_onload)(void*, void*);
    fp_onload fn_jol = (fp_onload)dlsym(h->h_engine, "JNI_OnLoad");
    if (fn_jol) {
        jint rv = fn_jol(&g_jvm, NULL);
        LOGI("引擎 JNI_OnLoad ret=%d", (int)rv);
    } else {
        LOGW("引擎没有 JNI_OnLoad，继续（可能与 rig 行为不同）");
    }

    SYM(h->h_engine, h->fn_ce,   "createEngineToNative");
    SYM(h->h_engine, h->fn_ie,   "initEngineToNative");
    SYM(h->h_engine, h->fn_sd,   "sendDirectiveToNative");
    SYM(h->h_engine, h->fn_ss,   "setOptionStringToNative");
    SYM(h->h_engine, h->fn_si,   "setOptionIntToNative");
    SYM(h->h_engine, h->fn_sb,   "setOptionBooleanToNative");
    SYM(h->h_engine, h->fn_sdbl, "setOptionDoubleToNative");
    SYM(h->h_engine, h->fn_de,   "destroyEngineToNative");
    SYM(h->h_engine, h->fn_gv,   "getVersionToNative");
    if (!h->fn_ce || !h->fn_ie || !h->fn_sd || !h->fn_ss || !h->fn_si) {
        set_error("引擎 JNI 导出不全（缺 create/init/directive/setOption）");
        goto fail_handle;
    }

    h->penv     = (JNIEnv*)&g_callback_env_holder;
    h->clazz    = (jclass)new_obj();
    h->callback = new_obj();
    h->engine   = h->fn_ce(h->penv, h->clazz, h->callback);
    LOGI("createEngineToNative handle=%lld", (long long)h->engine);
    if (!h->engine) { set_error("createEngineToNative 返回 0"); goto fail_handle; }

    /* 3) 选项串：逐条抄自 host2.c（原厂商 app 的 offline/pre_bind 档位）。 */
    setInt (h, "tts_work_mode", 2048);
    setStr (h, "engine_name", "tts");
    setStr (h, "authenticate_type", "pre_bind");
    setStr (h, "license_directory", res_root);
    setStr (h, "tts_scenario", "novel");
    setStr (h, "tts_text_type", "plain");
    setBool(h, "tts_enable_progress", 1);
    setBool(h, "prevent_player_creation", 1);
    setStr (h, "tts_voice_offline", "other");
    setStr (h, "tts_voice_type_offline", voice);
    setStr (h, "tracking_data_path", res_root);
    setStr (h, "settings_file_dir", res_root);
    setInt (h, "tts_rate", sample_rate);
    setInt (h, "sample_rate", sample_rate);
    setInt (h, "custom_sample_rate", sample_rate);
    setInt (h, "tts_pitch", 10);
    setInt (h, "tts_with_frontend", 0);
    setStr (h, "tts_frontend_type", "tnSimple");
    setInt (h, "tts_data_callback_mode", 2);       /* PCM 走 onSpeechMessage */
    setBool(h, "tts_enable_player", 0);
    setBool(h, "prevent_player_creation", 1);

    {
        char rc[4096];
        snprintf(rc, sizeof(rc),
                 "{\"base\":\"%s/%s\",\"voice\":[{\"%s\":\"%s/%s\"}]}",
                 res_root, base, voice, res_root, voice);
        setStr(h, "tts_off_resource_path", rc);
        LOGI("resource path = %s", rc);
    }

    snprintf(h->root,  sizeof(h->root),  "%s", res_root);
    snprintf(h->voice, sizeof(h->voice), "%s", voice);
    snprintf(h->base,  sizeof(h->base),  "%s", base);

    {
        jint r = h->fn_ie(h->penv, h->clazz, h->engine, NULL);
        LOGI("initEngineToNative ret=%d", (int)r);
        if (r != 0) {
            /* 引擎未必以 0 表示成功；只记日志，真正判据是冒烟合成有没有 PCM。 */
            LOGW("initEngine 返回 %d（rig 未把它当失败判据）", (int)r);
        }
    }
    return h;

fail_handle:
    free(h);
fail:
    if (err && err_cap > 0) snprintf(err, (size_t)err_cap, "%s", g_last_error);
    return NULL;
}

int midu_engine_synth(void *hp, const char *text, int speed, int pitch,
                      int volume, int timeout_ms) {
    return midu_engine_synth_ex(hp, text, speed, pitch, volume, timeout_ms,
                                NULL, NULL, NULL, 0);
}

int midu_engine_synth_ex(void *hp, const char *text, int speed, int pitch,
                         int volume, int timeout_ms,
                         const char* const* keys, const char* const* vals,
                         const int* types, int n) {
    MiduHandle *h = (MiduHandle*)hp;
    if (!h || !text) { set_error("synth 参数为空"); return -1; }
    if (timeout_ms <= 0) timeout_ms = 60000;

    pthread_mutex_lock(&h->lock);
    h->pcm_len = 0;
    h->end_marker = 0;
    h->stopped = 0;
    g_current = h;
    pthread_mutex_unlock(&h->lock);

    /* 先下发 rig 实测的三件套，再按序下发 UI / Legado 传来的扩展选项。
     * 扩展选项允许覆盖这三项（等价的另一种调法），但不能碰初始化档位。 */
    setStr(h, "tts_text", text);
    setInt(h, "tts_speed", speed);
    setInt(h, "tts_pitch", pitch);
    setInt(h, "tts_volume", volume);
    if (keys && vals && types && n > 0) {
        int applied = 0;
        for (int i = 0; i < n; i++) {
            if (apply_option(h, keys[i], vals[i], types[i])) applied++;
        }
        LOGI("本次合成下发 %d/%d 条扩展 tts_* 选项", applied, n);
    }

    jstring empty = (jstring)new_str("");
    h->fn_sd(h->penv, h->clazz, h->engine, 1000, empty);   /* START_ENGINE */
    jint rs = h->fn_sd(h->penv, h->clazz, h->engine, 1400, empty); /* SYNTHESIS */
    LOGI("sendDirective(1400) ret=%d", (int)rs);

    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec  += timeout_ms / 1000;
    deadline.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000L; }

    pthread_mutex_lock(&h->lock);
    while (!h->end_marker && !h->stopped) {
        int rc = pthread_cond_timedwait(&h->cond, &h->lock, &deadline);
        if (rc == ETIMEDOUT) break;
    }
    int done = h->end_marker;
    size_t pcm_bytes = h->pcm_len;
    g_current = NULL;
    pthread_mutex_unlock(&h->lock);

    if (!done) LOGW("合成未收到 1409 结束标记（timeout=%dms, pcm=%zu）", timeout_ms, pcm_bytes);
    if (pcm_bytes == 0) { set_error("合成结束但 0 字节 PCM（检查语音包/规则/授权）"); return -2; }
    return (int)pcm_bytes;
}

const uint8_t* midu_engine_pcm(void *hp, int *len) {
    MiduHandle *h = (MiduHandle*)hp;
    if (!h) { if (len) *len = 0; return NULL; }
    if (len) *len = (int)h->pcm_len;
    return h->pcm;
}

void midu_engine_stop(void *hp) {
    MiduHandle *h = (MiduHandle*)hp;
    if (!h) return;
    pthread_mutex_lock(&h->lock);
    h->stopped = 1;
    pthread_cond_broadcast(&h->cond);
    pthread_mutex_unlock(&h->lock);
    /* 注：rig 没验过 STOP 指令码，这里只做本地收流；下一次 synth 会重置状态。 */
}

typedef struct DestroyCtx { MiduHandle *h; } DestroyCtx;
static void* destroy_worker(void *p) {
    DestroyCtx *c = (DestroyCtx*)p;
    MiduHandle *h = c->h;
    if (h->fn_de) h->fn_de(h->penv, h->clazz, h->engine);
    free(h->pcm);
    pthread_mutex_destroy(&h->lock);
    pthread_cond_destroy(&h->cond);
    free(h);
    free(c);
    return NULL;
}
void midu_engine_destroy(void *hp) {
    if (!hp) return;
    /* destroyEngineToNative 在个别设备上会卡住；丢到分离线程里，不阻塞 TTS 线程。 */
    pthread_t t;
    DestroyCtx *c = (DestroyCtx*)calloc(1, sizeof(DestroyCtx));
    if (!c) return;
    c->h = (MiduHandle*)hp;
    if (pthread_create(&t, NULL, destroy_worker, c) == 0) pthread_detach(t);
    else free(c);
}
