/*
 * host2.c — 在 Linux(x86_64) 上借助 qemu-aarch64 + bionic 运行
 * 字节跳动离线 TTS 引擎 libspeechengine.so (com.lechuan.mdwz, petrel 引擎) 的桥接宿主。
 *
 * 调用契约(来自 com.lechuan.mdwz/speechengine/bridge/SpeechEngineBridge.java):
 *   createEngineToNative(SpeechBridgeCallback) -> long   [handle]
 *   setOptionStringToNative(long, String, String)
 *   setOptionIntToNative(long, String, int)
 *   setOptionBooleanToNative(long, String, boolean)
 *   setOptionDoubleToNative(long, String, double)
 *   initEngineToNative(long, AssetManager) -> int
 *   sendDirectiveToNative(long, int, String) -> int
 *   destroyEngineToNative(long)
 *
 * PCM 走 SpeechBridgeCallback.onSpeechMessage(int, byte[], int) 回调(在宿主侧由假 JNIEnv 捕获)。
 *
 * 重编（bionic・qemu-user 直接跑）・构建器＝NDK r29-beta3（`~/Android/Sdk/ndk/29.0.13846066`）：
 *   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang -o host2 host2.c
 *   ★32 位（armeabi-v7a 真机）**必须加 `-mthumb`**：
 *     $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi21-clang -mthumb -o host2 host2.c -ldl -llog
 *     引擎 armv7 库是 Thumb，host2 若按默认编成 ARM，JNI 函数表回调会以 Thumb 解释我们的 ARM 函数
 *     （执行流落进字面池）＝首次合成在 stub_CallObjectMethodV 处 SIGILL。MI PAD/95B357F6 实测。
 *   ★换件要在**没有 qemu 在跑**时做（`run/host2` 是指向本目录那枚 ELF 的符号链接・正在执行的 ELF 不许改写）・
 *     `src/tools/host2` 本身入库＝产线工装的一部分。
 */
#include "jni_local.h"
static int g_waitsec = 600;   /* ★等待线上限（env WAITSEC）・缺省 600s＝旧行为一字不变 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <ctype.h>
#include <stdarg.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <signal.h>
#include <ucontext.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <math.h>
/* ---- --serve 常驻服务：loopback TCP + 逐块 PCM 帧（一次性模式行为不变） ---- */
#include <errno.h>
#include <pthread.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

/* 工作线程崩溃后跳转至此, 结束当前线程(离线模式不需要 Cronet) */
__attribute__((noreturn)) static void thread_exit_tramp(void){
    extern void pthread_exit(void*) __attribute__((noreturn));
    pthread_exit(NULL);
#if defined(__aarch64__)
    for(;;) __asm__ __volatile__("wfi");
#else
    for(;;) sleep(1);   /* ARMv7 用户态跑 wfi 会 SIGILL */
#endif
}

/* SIGUSR1: dump current thread FP backtrace (manual FP walk) */
static void dbg_dump(int sig){
    (void)sig;
    fprintf(stderr,"\n[DBGDUMP] thread %ld\n",(long)gettid());
#if defined(__aarch64__)
    uint64_t fp;
    __asm__ __volatile__("mov %0, x29" : "=r"(fp));
    uint64_t pc; __asm__ __volatile__("mov %0, x30" : "=r"(pc));
    fprintf(stderr,"  #0 PC~%p (lr)\n",(void*)pc);
    uint64_t f=fp;
    for(int i=1;i<40;i++){
        if(!f||(f&0xf))break;
        uint64_t* p=(uint64_t*)f; uint64_t pf=p[0], rl=p[1];
        fprintf(stderr,"  #%d LR=%p\n",i,(void*)rl);
        if(!pf||pf<=f)break; f=pf;
    }
#else  /* ARMv7：FP＝r11・LR＝r14・APCS 帧里保存的 LR 在保存的 FP 之下 */
    unsigned fp;
    __asm__ __volatile__("mov %0, r11" : "=r"(fp));
    unsigned pc; __asm__ __volatile__("mov %0, r14" : "=r"(pc));
    fprintf(stderr,"  #0 PC~%p (lr)\n",(void*)pc);
    unsigned f=fp;
    for(int i=1;i<40;i++){
        if(!f)break;
        unsigned* p=(unsigned*)f; unsigned pf=p[0], rl=p[-1];
        fprintf(stderr,"  #%d LR=%p\n",i,(void*)rl);
        if(!pf||pf<=f)break; f=pf;
    }
#endif
    fflush(stderr);
}

static void crash_handler(int sig, siginfo_t* info, void* ucontext) {
    ucontext_t* uc = (ucontext_t*)ucontext;
    mcontext_t* mc = &uc->uc_mcontext;
#if defined(__aarch64__)
    uint64_t pc = mc->pc, fp = mc->regs[29], lr = mc->regs[30];
    fprintf(stderr, "\n[CRASH] thread=%ld signal %d si_addr=%p PC=%p FP=%p LR=%p\n",
            (long)gettid(), sig, info?info->si_addr:0,(void*)pc,(void*)fp,(void*)lr);
    fprintf(stderr,"  x0..x7 = %p %p %p %p %p %p %p %p\n",
        (void*)mc->regs[0],(void*)mc->regs[1],(void*)mc->regs[2],(void*)mc->regs[3],
        (void*)mc->regs[4],(void*)mc->regs[5],(void*)mc->regs[6],(void*)mc->regs[7]);
#else
    uint64_t pc = mc->arm_pc, fp = mc->arm_fp, lr = mc->arm_lr;
    fprintf(stderr, "\n[CRASH] thread=%ld signal %d si_addr=%p PC=%p FP=%p LR=%p\n",
            (long)gettid(), sig, info?info->si_addr:0,(void*)pc,(void*)fp,(void*)lr);
    fprintf(stderr,"  r0..r7 = %p %p %p %p %p %p %p %p\n",
        (void*)mc->arm_r0,(void*)mc->arm_r1,(void*)mc->arm_r2,(void*)mc->arm_r3,
        (void*)mc->arm_r4,(void*)mc->arm_r5,(void*)mc->arm_r6,(void*)mc->arm_r7);
#endif
    unsigned insn=0; memcpy(&insn,(void*)pc,4);
    fprintf(stderr,"  faulting insn @PC = %08x\n", insn);
#if defined(__aarch64__)
    uint64_t f=fp;
    for(int i=2;i<20;i++){
        if(!f||(f&0xf))break;
        uint64_t* fpv=(uint64_t*)f; uint64_t pf=fpv[0], rl=fpv[1];
        fprintf(stderr,"  #%d LR=%p (next_fp=%p)\n",i,(void*)rl,(void*)pf);
        if(!pf||pf<=f)break; f=pf;
    }
#else
    unsigned f=fp;
    for(int i=2;i<20;i++){
        if(!f||(f&3))break;
        unsigned* fpv=(unsigned*)f; unsigned pf=fpv[0], rl=fpv[-1];
        fprintf(stderr,"  #%d LR=%p (next_fp=%p)\n",i,(void*)rl,(void*)pf);
        if(!pf||pf<=f)break; f=pf;
    }
#endif
    {
        FILE* mf=fopen("/proc/self/maps","r");
        if(mf){ fprintf(stderr,"[MAPS]\n"); char line[512]; while(fgets(line,sizeof line,mf)) fputs(line,stderr); fclose(mf); }
    }
    /* 尝试恢复: 若故障在 libsscronet 工作线程, 直接结束该线程(离线不需要 Cronet) */
    {
#if defined(__aarch64__)
        uint64_t pc=uc->uc_mcontext.pc;
#else
        uint64_t pc=uc->uc_mcontext.arm_pc;
#endif
        uint64_t base=0, end=0;
        FILE* mf=fopen("/proc/self/maps","r");
        if(mf){
            char line[512];
            while(fgets(line,sizeof line,mf)){
                if(strstr(line,"libsscronet.so") && strstr(line,"r-xp")){
                    unsigned long long a,b;
                    if(sscanf(line,"%llx-%llx",&a,&b)==2){ base=a; end=b; }
                }
            }
            fclose(mf);
        }
        if(base && pc>=base && pc<end){
            fprintf(stderr,"[RECOVER] libsscronet worker crash @ +0x%llx -> pthread_exit(thread)\n",
                    (unsigned long long)(pc-base));
            /* 跳到安全出口: 调用 pthread_exit(NULL) 结束当前工作线程, 主线程继续 */
#if defined(__aarch64__)
            uc->uc_mcontext.pc = (uint64_t)(uintptr_t)&thread_exit_tramp;
#else
            uc->uc_mcontext.arm_pc = (unsigned long)(uintptr_t)&thread_exit_tramp;
#endif
            fflush(stderr);
            return;
        }
    }
    fflush(stderr); _exit(1);
}

/* ---------------- 假 Java 对象模型 ---------------- */
typedef struct FakeObj {
    int    type;   /* 0=string 1=byte[] 2=object(callback) */
    void  *data;
    size_t len;
    void  *iobj;
} FakeObj;

static jobject new_str(const char *s){
    FakeObj *o=calloc(1,sizeof*o); o->type=0;
    o->data=strdup(s?s:""); o->len=strlen((char*)o->data); return (jobject)o;
}
static jobject new_bytes(const void*p,size_t n){
    FakeObj*o=calloc(1,sizeof*o); o->type=1;
    o->data=malloc(n?n:1); if(p&&n)memcpy(o->data,p,n); o->len=n; return (jobject)o;
}
static jobject new_obj(void){ FakeObj*o=calloc(1,sizeof*o); o->type=2; return (jobject)o; }
static FakeObj* asObj(jobject o){ return (FakeObj*)o; }

/* ---------------- 通用桩 ---------------- */
static void g_stub(void){ fprintf(stderr,"[WARN] 未实现 JNI 函数被调用 (g_stub)\n"); }
#define SETSTUB(e,m) (e)->m=(__typeof__((e)->m))g_stub

/* ---------------- 需要实现的 JNI 函数 ---------------- */
static jint my_GetVersion(JNIEnv*e){ (void)e; return JNI_VERSION_1_6; }
static const char* my_GetStringUTFChars(JNIEnv*e,jstring s,jboolean*isCopy){
    (void)e; if(isCopy)*isCopy=JNI_FALSE; FakeObj*o=asObj(s);
    fprintf(stderr,"[STR] GetStringUTFChars len=%zu\n",o?o->len:0);
    return o?(char*)o->data:"";
}
static void my_ReleaseStringUTFChars(JNIEnv*e,jstring s,const char*c){(void)e;(void)s;(void)c;}
static jsize my_GetStringUTFLength(JNIEnv*e,jstring s){ (void)e; FakeObj*o=asObj(s); return o?(jsize)o->len:0; }
static jsize my_GetStringLength(JNIEnv*e,jstring s){ (void)e; FakeObj*o=asObj(s); return o?(jsize)o->len:0; }
static jstring my_NewStringUTF(JNIEnv*e,const char*utf){ (void)e; return new_str(utf?utf:""); }
static jsize my_GetArrayLength(JNIEnv*e,jarray a){ (void)e; FakeObj*o=asObj(a); return o?(jsize)o->len:0; }
static jbyteArray my_NewByteArray(JNIEnv*e,jsize n){ (void)e; return new_bytes(NULL,(size_t)(n>0?n:0)); }
static void my_GetByteArrayRegion(JNIEnv*e,jbyteArray a,jsize s,jsize l,jbyte*b){
    (void)e; FakeObj*o=asObj(a); if(o&&o->data) memcpy(b,(char*)o->data+s,l);
}
static void my_SetByteArrayRegion(JNIEnv*e,jbyteArray a,jsize s,jsize l,const jbyte*b){
    (void)e; FakeObj*o=asObj(a); if(o&&o->data&&o->len>=(size_t)(s+l)){
        memcpy((char*)o->data+s,b,l);
        if(l>=8){ unsigned v=0; memcpy(&v,b,4); fprintf(stderr,"[SBAR] s=%d l=%d first4=%08x\n",(int)s,(int)l,v); }
    }
}
static jbyte* my_GetByteArrayElements(JNIEnv*e,jbyteArray a,jboolean*isCopy){
    (void)e; FakeObj*o=asObj(a);
    fprintf(stderr,"[ARR] GetByteArrayElements len=%zu\n",o?o->len:0);
    if(isCopy)*isCopy=JNI_FALSE;
    return o?(jbyte*)o->data:NULL;
}
static void my_ReleaseByteArrayElements(JNIEnv*e,jbyteArray a,jbyte*elems,jint mode){
    (void)e; (void)mode;
    FakeObj*o=asObj(a);
    /* mode==0 (copy back) 已在原缓冲写入, 无需动作 */
    (void)elems; (void)o;
}
static void* my_GetPrimitiveArrayCritical(JNIEnv*e,jarray a,jboolean*isCopy){
    (void)e; FakeObj*o=asObj(a); if(isCopy)*isCopy=JNI_FALSE;
    return o?o->data:NULL;
}
static void my_ReleasePrimitiveArrayCritical(JNIEnv*e,jarray a,void*c,jint mode){
    (void)e;(void)a;(void)c;(void)mode;
}
static jobject my_NewGlobalRef(JNIEnv*e,jobject o){ (void)e; return o; }
static void my_DeleteGlobalRef(JNIEnv*e,jobject o){ (void)e;(void)o; }
static void my_DeleteLocalRef(JNIEnv*e,jobject o){ (void)e;(void)o; }
static jobject my_GetObjectClass(JNIEnv*e,jobject o){ (void)e;(void)o; return (jclass)new_obj(); }
static jboolean my_IsSameObject(JNIEnv*e,jobject a,jobject b){ (void)e; return (a==b)?JNI_TRUE:JNI_FALSE; }
static jboolean my_ExceptionCheck(JNIEnv*e){ (void)e; return JNI_FALSE; }

/* 回调方法 ID(存到 FakeID 类结构), 引擎会 GetMethodID(env, cls, "onSpeechMessage", "(I[BI)I") */
typedef struct FakeID { char name[64]; char sig[128]; void* data; } FakeID;
static jmethodID my_GetMethodID(JNIEnv*e,jclass c,const char*name,const char*sig){
    (void)e;(void)c; FakeID*m=calloc(1,sizeof*m);
    strncpy(m->name,name,63); strncpy(m->sig,sig?sig:"",127);
    fprintf(stderr,"[GETMID] %s %s\n",name,sig?sig:"");
    return (jmethodID)m;
}

/* ---- PCM 捕获: 引擎回调 onSpeechMessage(int, byte[], int) ---- */
static FILE* g_out=NULL; static long g_total=0; static volatile int g_end_marker=0;
/* 引擎 frontend 对「无可发音字符」的文本回调 1003（Error callback one），
   之后不会再吐音频也不发 1409；用它把等待从超时缩短到毫秒级。 */
static volatile int g_error_marker=0;
static int g_melfwd_dumped=0;

/* 常驻模式下 PCM 直接写进 socket 帧，不再落盘；一次性模式仍走 g_out。 */
static volatile int g_stream_fd = -1;
static volatile int g_listen_fd = -1;
static pthread_mutex_t g_frame_lock = PTHREAD_MUTEX_INITIALIZER;

static int fd_write_all(int fd, const void* buf, size_t n){
    const char* p=(const char*)buf; size_t off=0;
    while(off<n){
        ssize_t w=send(fd,p+off,n-off,MSG_NOSIGNAL);
        if(w>0){ off+=(size_t)w; continue; }
        if(w<0 && errno==EINTR) continue;
        return -1;
    }
    return 0;
}
static int fd_read_all(int fd, void* buf, size_t n){
    char* p=(char*)buf; size_t off=0;
    while(off<n){
        ssize_t r=recv(fd,p+off,n-off,0);
        if(r>0){ off+=(size_t)r; continue; }
        if(r<0 && errno==EINTR) continue;
        return -1;
    }
    return 0;
}
/* 读一行 ASCII（丢掉 \r），返回长度；-1 = 断开 / 超时 / 超长。 */
static int fd_read_line(int fd, char* buf, size_t cap){
    size_t i=0;
    while(i+1<cap){
        char ch; ssize_t r=recv(fd,&ch,1,0);
        if(r==0) return -1;
        if(r<0){ if(errno==EINTR) continue; return -1; }
        if(ch=='\n'){ buf[i]=0; return (int)i; }
        if(ch!='\r') buf[i++]=ch;
    }
    return -1;
}
static void serve_error(int fd, const char* msg){
    char hdr[64]; size_t n=strlen(msg);
    int h=snprintf(hdr,sizeof hdr,"X %zu\n",n);
    if(h>0) fd_write_all(fd,hdr,(size_t)h);
    fd_write_all(fd,msg,n);
}
static void stream_frame(const void* data, size_t n){
    int fd=g_stream_fd;
    if(fd<0 || n==0) return;
    char hdr[64]; int h=snprintf(hdr,sizeof hdr,"D %zu\n",n);
    pthread_mutex_lock(&g_frame_lock);
    if(h>0) fd_write_all(fd,hdr,(size_t)h);
    fd_write_all(fd,data,n);
    pthread_mutex_unlock(&g_frame_lock);
}
static void melfwd_dump(void){
    if(g_melfwd_dumped) return;
    g_melfwd_dumped=1;
    /* 首次 PCM 后 dump 内存: melgan 已推理, 中间张量在内存 */
    fprintf(stderr,"[MELFWD] dump on first PCM\n");
    FILE* mf=fopen("/proc/self/maps","r");
    if(mf){
        char line[512]; int idx=0;
        while(fgets(line,sizeof line,mf)){
            unsigned long long a,b; char perm[8]={0}; char path[256]={0};
            if(sscanf(line,"%llx-%llx %7s %*s %*s %*s %255s",&a,&b,perm,path)<3) continue;
            if(perm[0]!='r') continue;
            /* 跳过纯代码段(.so 的 rx) 但保留 .dat 文件映射 (melgan 权重可能 mmap) */
            if(strstr(path,".so")||strstr(path,".apk")||strstr(path,".jar")) continue;
            if(perm[1]=='x') continue;  /* 跳过可执行(代码)段 */
            if(b-a > (1ULL<<28) || b-a < 4096) continue;
            char outpath[256];
            snprintf(outpath,sizeof outpath,"/tmp/opencode/mf_%02d_%llx_%llx.bin",idx,a,b);
            FILE* wf=fopen(outpath,"wb");
            if(wf){
                unsigned long long wrote=0;
                while(wrote<b-a){
                    size_t chunk=(size_t)((b-a-wrote)<(1ULL<<20)?(b-a-wrote):(1ULL<<20));
                    size_t w=fwrite((void*)(uintptr_t)(a+wrote),1,chunk,wf);
                    if(w!=chunk) break;
                    wrote+=w;
                }
                fclose(wf);
                fprintf(stderr,"[MELFWD] dumped 0x%llx-0x%llx (%llu) -> %s\n",a,b,b-a,outpath);
            }
            idx++;
        }
        fclose(mf);
    }
    fprintf(stderr,"[MELFWD] dump done\n");
}
static void capture(jbyteArray pcm){
    FakeObj*o=asObj(pcm);
    if(o&&o->data&&o->len>0){
        int stream=(g_stream_fd>=0);
        /* 引擎在「非法文本 / 错误上报」时也会回调 1400，但只有一个 16B 状态包
           （first4 常为 0 或 JSON 片段）。它既不是 PCM，也不能计入 g_total，
           否则上层会收到一段假音频、再把失败判成「中途失败」。 */
        if(stream && o->len<64) return;
        g_total+=(long)o->len;
        if(stream) stream_frame(o->data,o->len);
        else if(g_out) fwrite(o->data,1,o->len,g_out);
        if(!stream) fprintf(stderr,"[pcm] +%zu bytes (total %ld)\n",o->len,g_total);
        if(!stream && g_out && getenv("MELFWD_DUMP")) melfwd_dump();
    }
}
static void melfwd_dump_end(void){
    /* 合成完成后 dump: melgan 全部块已推理, 中间张量(含 Conv_1 输出)在内存 */
    fprintf(stderr,"[MELEND] dump after full synthesis\n");
    FILE* mf=fopen("/proc/self/maps","r");
    if(mf){
        char line[512]; int idx=0;
        while(fgets(line,sizeof line,mf)){
            unsigned long long a,b; char perm[8]={0}; char path[256]={0};
            if(sscanf(line,"%llx-%llx %7s %*s %*s %*s %255s",&a,&b,perm,path)<3) continue;
            if(perm[0]!='r') continue;
            if(strstr(path,".so")||strstr(path,".apk")||strstr(path,".jar")) continue;
            if(perm[1]=='x') continue;
            if(b-a > (1ULL<<28) || b-a < 4096) continue;
            char outpath[256];
            snprintf(outpath,sizeof outpath,"/tmp/opencode/end_%02d_%llx_%llx.bin",idx,a,b);
            FILE* wf=fopen(outpath,"wb");
            if(wf){
                unsigned long long wrote=0;
                while(wrote<b-a){
                    size_t chunk=(size_t)((b-a-wrote)<(1ULL<<20)?(b-a-wrote):(1ULL<<20));
                    size_t w=fwrite((void*)(uintptr_t)(a+wrote),1,chunk,wf);
                    if(w!=chunk) break;
                    wrote+=w;
                }
                fclose(wf);
                fprintf(stderr,"[MELEND] dumped 0x%llx-0x%llx (%llu) -> %s\n",a,b,b-a,outpath);
            }
            idx++;
        }
        fclose(mf);
    }
    fprintf(stderr,"[MELEND] dump done\n");
}
static int is_speech_msg(FakeID*m){
    return m && (strcmp(m->name,"onSpeechMessage")==0);
}
static void pcm_from_varg(va_list ap){
    /* onSpeechMessage(int, byte[], int): 第2个参数是 byte[] */
    va_list ap2; va_copy(ap2,ap);
    int i1=va_arg(ap2,jint);
    jbyteArray b=va_arg(ap2,jbyteArray);
    int i2=va_arg(ap2,jint);
    va_end(ap2);
    fprintf(stderr,"[CB] onSpeechMessage(%d, b[%zu], %d)\n",i1, asObj(b)?asObj(b)->len:0, i2);
    if(i1==1400) capture(b);   /* TTS_AUDIO_DATA */
    if(i1==1409) g_end_marker=1; /* TTS_AUDIO_DATA_END */
    /* 1003 = 引擎 processor 报错。只在还没吐出任何 PCM 时当作「本句无音频」，
       正常句子此时 g_total 已经很大，不会被误判。 */
    if(i1==1003 && g_total<64) g_error_marker=1;
}
static void pcm_from_jval(const jvalue*a){ if(a) capture(a[1].l); }
static jint my_CallIntMethodV(JNIEnv*e,jobject o,jmethodID mid,va_list ap){
    (void)e;(void)o; FakeID*m=(FakeID*)mid;
    fprintf(stderr,"[CALL] Int.%s\n",m?m->name:"?");
    if(is_speech_msg(m)) pcm_from_varg(ap);
    return 0;
}
static jint my_CallIntMethod(JNIEnv*e,jobject o,jmethodID mid,...){
    (void)e;(void)o; va_list ap; va_start(ap,mid);
    if(is_speech_msg((FakeID*)mid)) pcm_from_varg(ap);
    va_end(ap); return 0;
}
static jint my_CallIntMethodA(JNIEnv*e,jobject o,jmethodID mid,const jvalue*a){
    (void)e;(void)o; if(is_speech_msg((FakeID*)mid)) pcm_from_jval(a); return 0;
}
static void my_CallVoidMethodV(JNIEnv*e,jobject o,jmethodID mid,va_list ap){(void)e;(void)o;
    FakeID*m=(FakeID*)mid; fprintf(stderr,"[CALL] Void.%s\n",m?m->name:"?");
    if(is_speech_msg(m)) pcm_from_varg(ap);}
static void my_CallVoidMethod(JNIEnv*e,jobject o,jmethodID mid,...){(void)e;(void)o; va_list ap; va_start(ap,mid);
    if(is_speech_msg((FakeID*)mid)) pcm_from_varg(ap); va_end(ap);}
static void my_CallVoidMethodA(JNIEnv*e,jobject o,jmethodID mid,const jvalue*a){(void)e;(void)o;
    if(is_speech_msg((FakeID*)mid)) pcm_from_jval(a);}
static jobject my_CallObjectMethodV(JNIEnv*e,jobject o,jmethodID mid,va_list ap){(void)e;(void)o;(void)ap;
    FakeID*m=(FakeID*)mid; fprintf(stderr,"[CALL] Obj.%s\n",m?m->name:"?");
    if(m && (strcmp(m->name,"getPackageName")==0||strcmp(m->name,"toString")==0||strcmp(m->name,"getApplication")==0))
        return new_str("com.lechuan.mdwz");
    return new_obj();}
static jobject my_CallObjectMethod(JNIEnv*e,jobject o,jmethodID mid,...){(void)e;(void)o; FakeID*m=(FakeID*)mid;
    if(m && (strcmp(m->name,"getPackageName")==0||strcmp(m->name,"toString")==0||strcmp(m->name,"getApplication")==0))
        return new_str("com.lechuan.mdwz");
    return new_obj();}
static jobject my_CallObjectMethodA(JNIEnv*e,jobject o,jmethodID mid,const jvalue*a){(void)e;(void)o;(void)a; FakeID*m=(FakeID*)mid;
    if(m && (strcmp(m->name,"getPackageName")==0||strcmp(m->name,"toString")==0||strcmp(m->name,"getApplication")==0))
        return new_str("com.lechuan.mdwz");
    return new_obj();}

/* ---- JavaVM: 引擎回调线程会 AttachCurrentThread / GetEnv ----
 * C 模式下 JNIEnv 是 `const struct JNINativeInterface*`, 引擎(C++)里 env 指向
 * 一个 { const JNINativeInterface* functions; } 对象。我们用 env_holder 模拟。 */
static const struct JNINativeInterface* g_fake_table;
static struct { const struct JNINativeInterface* functions; } g_callback_env_holder;
static jint fake_GetEnv(void* vm, void** env, jint version){
    (void)vm;(void)version; *env=(void*)&g_callback_env_holder; return JNI_OK;
}
static jint fake_AttachCurrentThread(void* vm, void** env, void* args){
    (void)vm;(void)args; *env=(void*)&g_callback_env_holder; return JNI_OK;
}
static jint fake_DetachCurrentThread(void* vm){ (void)vm; return JNI_OK; }
static jint fake_DestroyJavaVM(void* vm){ (void)vm; return JNI_OK; }

#include "jnienv_stub.h"
static void* make_env_holder(void){
    static struct JNINativeInterface env; memset(&env,0,sizeof env); init_jni_stubs(&env);
    env.GetVersion=my_GetVersion;
    env.GetStringUTFChars=my_GetStringUTFChars;
    env.ReleaseStringUTFChars=my_ReleaseStringUTFChars;
    env.GetStringUTFLength=my_GetStringUTFLength;
    env.GetStringLength=my_GetStringLength;
    env.NewStringUTF=my_NewStringUTF;
    env.GetArrayLength=my_GetArrayLength;
    env.NewByteArray=my_NewByteArray;
    env.GetByteArrayRegion=my_GetByteArrayRegion;
    env.SetByteArrayRegion=my_SetByteArrayRegion;
    env.GetByteArrayElements=my_GetByteArrayElements;
    env.ReleaseByteArrayElements=my_ReleaseByteArrayElements;
    env.GetPrimitiveArrayCritical=my_GetPrimitiveArrayCritical;
    env.ReleasePrimitiveArrayCritical=my_ReleasePrimitiveArrayCritical;
    env.NewGlobalRef=my_NewGlobalRef;
    env.DeleteGlobalRef=my_DeleteGlobalRef;
    env.DeleteLocalRef=my_DeleteLocalRef;
    env.GetObjectClass=my_GetObjectClass;
    env.IsSameObject=my_IsSameObject;
    env.ExceptionCheck=my_ExceptionCheck;
    env.GetMethodID=my_GetMethodID;
    env.CallIntMethod=my_CallIntMethod;
    env.CallIntMethodV=my_CallIntMethodV;
    env.CallIntMethodA=my_CallIntMethodA;
    env.CallVoidMethod=my_CallVoidMethod;
    env.CallVoidMethodV=my_CallVoidMethodV;
    env.CallVoidMethodA=my_CallVoidMethodA;
    env.CallObjectMethod=my_CallObjectMethod;
    env.CallObjectMethodV=my_CallObjectMethodV;
    env.CallObjectMethodA=my_CallObjectMethodA;
    return &env;
}

/* ---------------- JNI 函数指针类型(SpeechEngineBridge) ---------------- */
typedef jlong   (*fp_createEngine)(JNIEnv*, jclass, jobject);
typedef jint    (*fp_initEngine)(JNIEnv*, jclass, jlong, jobject);
typedef jint    (*fp_sendDirective)(JNIEnv*, jclass, jlong, jint, jstring);
typedef void    (*fp_setOptStr)(JNIEnv*, jclass, jlong, jstring, jstring);
typedef void    (*fp_setOptInt)(JNIEnv*, jclass, jlong, jstring, jint);
typedef void    (*fp_setOptBool)(JNIEnv*, jclass, jlong, jstring, jboolean);
typedef void    (*fp_setOptDbl)(JNIEnv*, jclass, jlong, jstring, jdouble);
typedef void    (*fp_destroy)(JNIEnv*, jclass, jlong);
typedef jstring (*fp_getVersion)(JNIEnv*, jclass);

static void* load(const char* path){ void*h=dlopen(path,RTLD_NOW|RTLD_GLOBAL);
    if(!h)fprintf(stderr,"dlopen %s 失败: %s\n",path,dlerror()); return h; }
static void* sy(void*h,const char*n){ void*p=dlsym(h,n); if(!p)fprintf(stderr,"dlsym %s 失败\n",n); return p; }

/* 每句批量下发选项时，19 行 [setOpt] 会把长篇朗读的日志刷爆：汇总成一行打印。 */
static int g_opt_quiet = 0;

static void setStr(JNIEnv* e, jclass cl, fp_setOptStr fn, jlong h, const char*k, const char*v){
    if(!fn) return;
    jobject jk=new_str(k), jv=new_str(v?v:"");
    if(!g_opt_quiet) fprintf(stderr,"[setOpt] %s = %s\n",k,v?v:"");
    fn(e,cl,h,jk,jv);
}
static void setInt(JNIEnv* e, jclass cl, fp_setOptInt fn, jlong h, const char*k, int v){
    if(!fn) return;
    jobject jk=new_str(k);
    if(!g_opt_quiet) fprintf(stderr,"[setOpt] %s = %d\n",k,v);
    fn(e,cl,h,jk,(jint)v);
}
static void setBool(JNIEnv* e, jclass cl, fp_setOptBool fn, jlong h, const char*k, int v){
    if(!fn) return;
    jobject jk=new_str(k);
    if(!g_opt_quiet) fprintf(stderr,"[setOpt] %s = %d\n",k,v);
    fn(e,cl,h,jk,v?JNI_TRUE:JNI_FALSE);
}
static void setDbl(JNIEnv* e, jclass cl, fp_setOptDbl fn, jlong h, const char*k, double v){
    if(!fn) return;
    jobject jk=new_str(k);
    if(!g_opt_quiet) fprintf(stderr,"[setOpt] %s = %g\n",k,v);
    fn(e,cl,h,jk,(jdouble)v);
}


/* =========================== 常驻服务（--serve） ===========================
 * 模型只加载一次，之后每个连接可连续发多句；PCM 每来一块就发一帧，
 * app 侧因此可以边收边播 / 边收边写 HTTP chunk，而不是等整段合成完。
 *
 * 协议（TCP 127.0.0.1；命令行是 ASCII 行，PCM 是二进制）：
 *   C: P\n                        -> S: PONG\n          探活
 *   C: V\n                        -> S: VER 2\n         能力协商（v2 才支持 O；旧版回 X）
 *   C: A <token>\n                -> 校验（仅当 --token 非空时需要）
 *   C: O <n>\n + n 行 "<type>\t<key>\t<value>\n"
 *                                 本句的完整选项快照，作用于下一条 S；不回响应
 *   C: S <speed> <text_len>\n  +  <text_len 字节 UTF-8 文本>
 *   S: D <n>\n + <n 字节 PCM>     每个回调块一帧，可重复
 *   S: E <total>\n                本句结束
 *   S: X <len>\n + <错误文本>     失败
 *   C: Q\n                        -> daemon 退出
 * 同一连接可连发多句，省掉每句一次握手；daemon 串行处理，天然串行化引擎调用。
 * type 与 app 侧 EngineOption 一致：0=string 1=int 2=bool 3=double。
 */
struct ServeCtx {
    JNIEnv* env; jclass clazz; jlong handle;
    fp_setOptStr setStrFn; fp_setOptInt setIntFn;
    fp_setOptBool setBoolFn; fp_setOptDbl setDblFn;
    fp_sendDirective sendDir;
    int port; const char* token; int waitSec;
};

/* ---- 每句选项覆盖（协议 v2） --------------------------------------------
 * 引擎句柄是复用的，setOption 是持久状态且引擎没有 clearOption。O 命令带的是
 * 「本句完整快照」：先对上一句有、本句没有的键回填中性值（防 emotion/style
 * 跨句残留），再逐条应用本句选项。app 侧每句都发全量列表，所以正常路径下
 * cleared 恒为 0。 */
#define SVO_MAX 64
#define SVO_KEY 64
#define SVO_VAL 1024
struct ServeOpt { int type; char key[SVO_KEY]; char val[SVO_VAL]; };

/* 与 app/src/main/jni/fake_jni.c 的 RESERVED_KEYS 一致：这些是 rig 钉死的
   初始化档位，改了会破坏授权 / 资源定位 / PCM 回调。O 来自 socket，必须挡。 */
static const char* SVO_RESERVED[] = {
    "tts_text", "tts_work_mode", "engine_name", "authenticate_type",
    "license_directory", "settings_file_dir", "tracking_data_path",
    "tts_off_resource_path", "tts_voice_offline", "tts_voice_type_offline",
    "tts_data_callback_mode", "tts_enable_player", "prevent_player_creation",
    "tts_rate", "sample_rate", "custom_sample_rate",
    "tts_with_frontend", "tts_frontend_type", "tts_enable_progress",
    "tts_scenario", "tts_enable_dump"
};
static int svo_allowed(const char* k){
    if(!k || !*k) return 0;
    if(strncmp(k,"tts_",4) && strncmp(k,"voiceconv_",10)) return 0;
    for(size_t i=0;i<sizeof(SVO_RESERVED)/sizeof(SVO_RESERVED[0]);i++)
        if(!strcmp(k,SVO_RESERVED[i])) return 0;
    return 1;
}

static void svo_apply_one(struct ServeCtx* c, const struct ServeOpt* o, int clearing){
    const char* v = clearing ? "" : o->val;
    switch(o->type){
        case 1: setInt (c->env,c->clazz,c->setIntFn,c->handle,o->key,atoi(v)); break;
        case 2: setBool(c->env,c->clazz,c->setBoolFn,c->handle,o->key,
                        v[0]=='1'||v[0]=='t'||v[0]=='T'); break;
        case 3: setDbl (c->env,c->clazz,c->setDblFn,c->handle,o->key,atof(v)); break;
        default: setStr (c->env,c->clazz,c->setStrFn,c->handle,o->key,v); break;
    }
}

static void svo_apply(struct ServeCtx* c, const struct ServeOpt* cur, int ncur,
                      struct ServeOpt* prev, int* nprev){
    int applied=0, cleared=0;
    g_opt_quiet=1;
    for(int i=0;i<*nprev;i++){
        int found=0;
        for(int j=0;j<ncur;j++) if(!strcmp(prev[i].key,cur[j].key)){ found=1; break; }
        if(!found){ svo_apply_one(c,&prev[i],1); cleared++; }
    }
    for(int j=0;j<ncur;j++){
        if(!svo_allowed(cur[j].key)) continue;
        svo_apply_one(c,&cur[j],0);
        applied++;
    }
    g_opt_quiet=0;
    /* 一行汇总即可：既不会像逐条 [setOpt] 那样刷屏，又能在 serve_boot.log 里
       直接核对「调用方每句覆盖」到底有没有落到引擎上。 */
    fprintf(stderr,"[serve][opts] applied=%d cleared=%d",applied,cleared);
    for(int j=0;j<ncur;j++)
        if(svo_allowed(cur[j].key)) fprintf(stderr," %s=%s",cur[j].key,cur[j].val);
    fprintf(stderr,"\n");
    fflush(stderr);
    *nprev=0;
    for(int j=0;j<ncur && *nprev<SVO_MAX;j++){
        if(!svo_allowed(cur[j].key)) continue;
        prev[(*nprev)++]=cur[j];
    }
}

static int g_fail_streak = 0;

static int synth_one(struct ServeCtx* c, int fd, const char* text, int speed,
                     const struct ServeOpt* opts, int nopt,
                     struct ServeOpt* prev, int* nprev){
    char tail[64]; int h;
    g_total=0; g_end_marker=0; g_error_marker=0;
    pthread_mutex_lock(&g_frame_lock); g_stream_fd=fd; pthread_mutex_unlock(&g_frame_lock);

    setStr (c->env,c->clazz,c->setStrFn,c->handle,"tts_text",text);
    setInt (c->env,c->clazz,c->setIntFn,c->handle,"tts_speed",speed);
    setInt (c->env,c->clazz,c->setIntFn,c->handle,"tts_volume",100);
    { const char* v;
      v=getenv("TTS_SIL");        if(v&&*v) setInt(c->env,c->clazz,c->setIntFn,c->handle,"tts_silence_duration",atoi(v));
      v=getenv("TTS_SUBLEN");     if(v&&*v) setInt(c->env,c->clazz,c->setIntFn,c->handle,"tts_sub_sentence_length",atoi(v));
      v=getenv("TTS_SPEED_RATIO");if(v&&*v) setInt(c->env,c->clazz,c->setIntFn,c->handle,"tts_speed_ratio",atoi(v)); }
    /* v2：每句 O 快照放在启动 env 之后下发，调用方覆盖优先于 app 启动预设。 */
    svo_apply(c,opts,nopt,prev,nprev);
    { jobject jd0=new_str(""); c->sendDir(c->env,c->clazz,c->handle,1000,jd0); }
    { jobject jd =new_str(""); c->sendDir(c->env,c->clazz,c->handle,1400,jd);  }

    /* 单句最多等 20 s。原来跟 WAITSEC(120 s) 等长：Legado 会单独传来只有
       引号/破折号的片段，引擎 frontend 报 illegal text 后既不吐 PCM 也不发
       1409；daemon 单线程 accept 因此把后续所有请求堵在 backlog，表现就是
       「朗读一句就停，再点没反应」。同时探测客户端断开，TTS stop() 后立刻
       放弃本句，不要继续占着唯一的连接。 */
    int max_polls=c->waitSec*10;
    if(max_polls>200) max_polls=200;
    for(int w=0; w<max_polls; w++){
        usleep(100000);
        if(g_end_marker || g_error_marker) break;
        char probe; ssize_t r=recv(fd,&probe,1,MSG_PEEK|MSG_DONTWAIT);
        if(r==0 || (r<0 && errno!=EAGAIN && errno!=EWOULDBLOCK && errno!=EINTR)){
            pthread_mutex_lock(&g_frame_lock); g_stream_fd=-1; pthread_mutex_unlock(&g_frame_lock);
            fprintf(stderr,"[serve] 客户端已断开，放弃本句\n"); fflush(stderr);
            return -2;
        }
    }
    pthread_mutex_lock(&g_frame_lock); g_stream_fd=-1; pthread_mutex_unlock(&g_frame_lock);

    if(g_total<64){
        /* 无可发音字符（或引擎空转）不算故障：补一帧 80 ms 静音 + E，
           让上层正常 onDone 并继续下一句。app 侧 Speakable 已拦掉绝大多数，
           这里是 daemon 侧兜底，避免任何漏网片段再次堵死整个服务。 */
        static const short silence[1920]={0};   /* 24000Hz * 0.08s * 16bit mono */
        fprintf(stderr,"[serve] 本句无有效音频(end=%d pcm=%ldB)，补 80ms 静音继续\n",
                g_end_marker,g_total); fflush(stderr);
        h=snprintf(tail,sizeof tail,"D %zu\n",sizeof silence);
        if(h>0) fd_write_all(fd,tail,(size_t)h);
        fd_write_all(fd,silence,sizeof silence);
        g_fail_streak=0;
        h=snprintf(tail,sizeof tail,"E %zu\n",sizeof silence);
        if(h>0) fd_write_all(fd,tail,(size_t)h);
        return 0;
    }
    if(!g_end_marker){
        /* 已经吐出音频却没等到结束标记，才像引擎状态损坏：报错并累计自愈。 */
        char msg[128];
        snprintf(msg,sizeof msg,"合成超时：end=0 pcm=%ldB",g_total);
        fprintf(stderr,"[serve] %s\n",msg); fflush(stderr);
        serve_error(fd,msg);
        if(++g_fail_streak>=3){ fflush(stderr); _exit(70); }  /* 让 app 重新拉起，等于重建引擎 */
        return -1;
    }
    g_fail_streak=0;
    h=snprintf(tail,sizeof tail,"E %ld\n",g_total);
    if(h>0) fd_write_all(fd,tail,(size_t)h);
    fprintf(stderr,"[serve] 一句完成 PCM=%ldB speed=%d\n",g_total,speed);
    return 0;
}

static void serve_client(struct ServeCtx* c, int fd){
    struct timeval tv; char line[SVO_VAL + SVO_KEY + 64];
    struct ServeOpt cur[SVO_MAX], prev[SVO_MAX];
    int ncur=0, nprev=0;
    int authed=(c->token && *c->token) ? 0 : 1;
    tv.tv_sec=180; tv.tv_usec=0;
    setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&tv,sizeof tv);
    setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&tv,sizeof tv);
    for(;;){
        int n=fd_read_line(fd,line,sizeof line);
        if(n<0) return;
        if(!strcmp(line,"P")){ fd_write_all(fd,"PONG\n",5); continue; }
        if(!strcmp(line,"V")){ fd_write_all(fd,"VER 2\n",6); continue; }
        if(!strcmp(line,"Q")){ fd_write_all(fd,"BYE\n",4); fflush(stderr); _exit(0); }
        if(line[0]=='A' && line[1]==' '){
            authed=(c->token && !strcmp(line+2,c->token)) ? 1 : 0;
            if(!authed){ serve_error(fd,"token 校验失败"); return; }
            continue;
        }
        if(line[0]=='O' && line[1]==' '){
            int cnt=atoi(line+2);
            if(!authed){ serve_error(fd,"需要先发 A <token>"); return; }
            if(cnt<0 || cnt>SVO_MAX){ serve_error(fd,"O 数量越界"); return; }
            ncur=0;
            for(int i=0;i<cnt;i++){
                if(fd_read_line(fd,line,sizeof line)<0) return;
                char* t1=strchr(line,'\t');
                char* t2=t1?strchr(t1+1,'\t'):NULL;
                if(!t1 || !t2){ serve_error(fd,"O 行格式错（应为 type\\tkey\\tvalue）"); return; }
                *t1=0; *t2=0;
                const char* k=t1+1, *v=t2+1;
                if(strlen(k)>=SVO_KEY || strlen(v)>=SVO_VAL){
                    serve_error(fd,"O 键/值过长"); return; }
                cur[ncur].type=atoi(line);
                snprintf(cur[ncur].key,sizeof cur[ncur].key,"%s",k);
                snprintf(cur[ncur].val,sizeof cur[ncur].val,"%s",v);
                ncur++;
            }
            continue;
        }
        if(line[0]=='S' && line[1]==' '){
            int speed=10; unsigned int tlen=0;
            if(!authed){ serve_error(fd,"需要先发 A <token>"); return; }
            if(sscanf(line+2,"%d %u",&speed,&tlen)!=2 || tlen==0 || tlen>65536){
                serve_error(fd,"S 行格式错，或文本超 64KB"); return; }
            char* text=(char*)malloc((size_t)tlen+1);
            if(!text){ serve_error(fd,"内存不足"); return; }
            if(fd_read_all(fd,text,tlen)!=0){ free(text); return; }
            text[tlen]=0;
            int rc=synth_one(c,fd,text,speed,cur,ncur,prev,&nprev);
            free(text);
            ncur=0;                 /* O 只作用于紧随其后的一条 S */
            if(rc!=0) return;
            continue;
        }
        serve_error(fd,"未知命令");
        return;
    }
}

static int serve_loop(struct ServeCtx* c){
    int srv=g_listen_fd;
    if(srv<0) return 21;
    fprintf(stderr,"[serve] ready 127.0.0.1:%d（引擎已 init，同一连接可发多句）\n",c->port);
    fflush(stderr);
    for(;;){
        int fd=accept(srv,NULL,NULL);
        if(fd<0){ if(errno==EINTR) continue; usleep(200000); continue; }
        serve_client(c,fd);
        close(fd);
    }
    return 0;
}

int main(int argc, char** argv){
    struct sigaction sa; memset(&sa,0,sizeof sa); sa.sa_sigaction=crash_handler; sa.sa_flags=SA_SIGINFO;
    sigemptyset(&sa.sa_mask); sigaction(SIGSEGV,&sa,NULL); sigaction(SIGBUS,&sa,NULL);
    signal(SIGUSR1, dbg_dump);
    signal(SIGPIPE, SIG_IGN);   /* 客户端断开时别让 daemon 被 SIGPIPE 干掉 */

    const char* res_root=NULL;   /* 资源根: <...>/voice/bytedance/midu/ */
    const char* voice="BV001_24k_streaming";
    const char* base="zh-cn";
    const char* text="你好世界, 这是一个测试。";
    const char* out="out2.pcm";
    int do_synth=0;
    int serve_mode=0, serve_port=18945;
    const char* serve_token="";

    for(int i=1;i<argc;i++){
        if(!strcmp(argv[i],"--root")&&i+1<argc) res_root=argv[++i];
        else if(!strcmp(argv[i],"--voice")&&i+1<argc) voice=argv[++i];
        else if(!strcmp(argv[i],"--base")&&i+1<argc) base=argv[++i];
        else if(!strcmp(argv[i],"--text")&&i+1<argc) text=argv[++i];
        else if(!strcmp(argv[i],"--out")&&i+1<argc) out=argv[++i];
        else if(!strcmp(argv[i],"--synth")) do_synth=1;
        else if(!strcmp(argv[i],"--serve")) serve_mode=1;
        else if(!strcmp(argv[i],"--port")&&i+1<argc) serve_port=atoi(argv[++i]);
        else if(!strcmp(argv[i],"--token")&&i+1<argc) serve_token=argv[++i];
    }
    if(!res_root){ fprintf(stderr,
        "用法: host2 --root <midu根目录> [--voice ...] [--base ...] [--text ...] [--out ...] [--synth]\n"
        "      host2 --root <midu根目录> --serve [--port 18945] [--token XXX]\n"); return 1; }

    /* --serve：先占住端口再慢慢加载模型。app 侧「能连上端口」即视为 daemon 已起，
       请求会在内核 backlog 里排队，等引擎 init 完成才被 accept，首次延迟只付一次。
       端口已被占用说明已有 daemon 在跑，直接退出，不要抢。 */
    if(serve_mode){
        int srv=socket(AF_INET,SOCK_STREAM,0), on=1;
        struct sockaddr_in a;
        if(srv<0){ fprintf(stderr,"[serve] socket 失败: %s\n",strerror(errno)); return 20; }
        setsockopt(srv,SOL_SOCKET,SO_REUSEADDR,&on,sizeof on);
        memset(&a,0,sizeof a);
        a.sin_family=AF_INET; a.sin_port=htons((unsigned short)serve_port);
        a.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        if(bind(srv,(struct sockaddr*)&a,sizeof a)<0){
            fprintf(stderr,"[serve] bind 127.0.0.1:%d 失败（多半已有 daemon）：%s\n",
                    serve_port,strerror(errno));
            close(srv); return 0;
        }
        if(listen(srv,8)<0){ close(srv); return 20; }
        g_listen_fd=srv;
        fprintf(stderr,"[serve] 端口已监听，开始加载引擎（首次约需几十秒）...\n");
        fflush(stderr);
    }

    /* 依赖库按需 preload(引擎依赖 libsscronet/libttboringssl/libttcrypto 等) */
    load("libsscronet.so");
    load("libttboringssl.so");
    load("libttcrypto.so");
    void* h=load("libspeechengine.so");
    if(!h) return 2;

    fp_createEngine fn_ce=(fp_createEngine)sy(h,"Java_com_bytedance_speech_speechengine_bridge_SpeechEngineBridge_createEngineToNative");
    fp_initEngine    fn_ie=(fp_initEngine)sy(h,"Java_com_bytedance_speech_speechengine_bridge_SpeechEngineBridge_initEngineToNative");
    fp_sendDirective fn_sd=(fp_sendDirective)sy(h,"Java_com_bytedance_speech_speechengine_bridge_SpeechEngineBridge_sendDirectiveToNative");
    fp_setOptStr     fn_ss=(fp_setOptStr)sy(h,"Java_com_bytedance_speech_speechengine_bridge_SpeechEngineBridge_setOptionStringToNative");
    fp_setOptInt     fn_si=(fp_setOptInt)sy(h,"Java_com_bytedance_speech_speechengine_bridge_SpeechEngineBridge_setOptionIntToNative");
    fp_setOptBool    fn_sb=(fp_setOptBool)sy(h,"Java_com_bytedance_speech_speechengine_bridge_SpeechEngineBridge_setOptionBooleanToNative");
    fp_setOptDbl     fn_sdbl=(fp_setOptDbl)sy(h,"Java_com_bytedance_speech_speechengine_bridge_SpeechEngineBridge_setOptionDoubleToNative");
    fp_destroy       fn_de=(fp_destroy)sy(h,"Java_com_bytedance_speech_speechengine_bridge_SpeechEngineBridge_destroyEngineToNative");
    if(!fn_ce||!fn_ie||!fn_sd||!fn_ss) return 3;

    void* envp=make_env_holder();
    g_fake_table=(const struct JNINativeInterface*)envp;
    g_callback_env_holder.functions=g_fake_table;
    JNIEnv* penv=(JNIEnv*)&g_callback_env_holder;
    jclass clazz=(jclass)new_obj();
    jobject callback=new_obj();

    /* 假 JavaVM: 回调线程 AttachCurrentThread/GetEnv 返回上面的 env */
    struct JNIInvokeInterface inv = {0};
    inv.DestroyJavaVM=(void*)fake_DestroyJavaVM;
    inv.AttachCurrentThread=(void*)fake_AttachCurrentThread;
    inv.DetachCurrentThread=(void*)fake_DetachCurrentThread;
    inv.GetEnv=(void*)fake_GetEnv;
    struct _JavaVM jvm; jvm.functions=&inv;

    /* 1) 引擎需要 JNI_OnLoad(vm, NULL) 先建立 JavaVM 引用(JniEnvironment::GetEnvOnce 用) */
    {
        typedef jint (*fp_onload)(void*, void*);
        fp_onload fn_jol=(fp_onload)dlsym(h,"JNI_OnLoad");
        if(fn_jol){
            fprintf(stderr,"[+] JNI_OnLoad(vm) ...\n");
            jint rv=fn_jol(&jvm,NULL);
            fprintf(stderr,"[+] JNI_OnLoad ret=%d\n",(int)rv);
        } else { fprintf(stderr,"[!] 找不到 JNI_OnLoad\n"); }
    }

    /* 2) createEngineToNative(callback) -> handle */
    fprintf(stderr,"[+] createEngineToNative ...\n");
    jlong handle=fn_ce(penv,clazz,callback);
    fprintf(stderr,"[+] handle = %lld (0x%llx)\n",(long long)handle,(unsigned long long)handle);

    /* 2) 引擎选项(来自 p034n4/a.java c()) */
    setInt (penv,clazz,fn_si,handle,"tts_work_mode",2048);        /* offline */
    setStr (penv,clazz,fn_ss,handle,"engine_name","tts");
    setStr (penv,clazz,fn_ss,handle,"authenticate_type","pre_bind");
    setStr (penv,clazz,fn_ss,handle,"license_directory",res_root);
    setStr (penv,clazz,fn_ss,handle,"tts_scenario","novel");
    setStr (penv,clazz,fn_ss,handle,"tts_text_type","plain");
    setBool(penv,clazz,fn_sb,handle,"tts_enable_progress",1);
    setBool(penv,clazz,fn_sb,handle,"prevent_player_creation",1);
    setStr (penv,clazz,fn_ss,handle,"tts_voice_offline","other");
    setStr (penv,clazz,fn_ss,handle,"tts_voice_type_offline",voice);
    setStr (penv,clazz,fn_ss,handle,"tracking_data_path",res_root);
    setStr (penv,clazz,fn_ss,handle,"settings_file_dir",res_root);
    setInt (penv,clazz,fn_si,handle,"tts_rate",24000);
    setInt (penv,clazz,fn_si,handle,"sample_rate",24000);
    setInt (penv,clazz,fn_si,handle,"custom_sample_rate",24000);
    setInt (penv,clazz,fn_si,handle,"tts_pitch",10);
    setInt (penv,clazz,fn_si,handle,"tts_with_frontend",0);
    setStr (penv,clazz,fn_ss,handle,"tts_frontend_type","tnSimple");
    setInt (penv,clazz,fn_si,handle,"tts_data_callback_mode",2);   /* ALL: PCM 走 onSpeechMessage 回调 */
    setBool(penv,clazz,fn_sb,handle,"tts_enable_player",0);
    setBool(penv,clazz,fn_sb,handle,"prevent_player_creation",1);

    /* tts_off_resource_path = ResourceConfig JSON */
    char rc[8192];
    snprintf(rc,sizeof rc,
        "{\"base\":\"%s/%s\",\"voice\":[{\"%s\":\"%s/%s\"}]}",
        res_root, base, voice, res_root, voice);
    setStr(penv,clazz,fn_ss,handle,"tts_off_resource_path",rc);

    /* 3) initEngineToNative(handle, NULL) */
    fprintf(stderr,"[+] initEngineToNative ...\n");
    jint rinit=fn_ie(penv,clazz,handle,NULL);
    fprintf(stderr,"[+] initEngine ret=%d\n",(int)rinit);

    if(serve_mode){
        struct ServeCtx sc; const char* w;
        memset(&sc,0,sizeof sc);
        sc.env=penv; sc.clazz=clazz; sc.handle=handle;
        sc.setStrFn=fn_ss; sc.setIntFn=fn_si;
        sc.setBoolFn=fn_sb; sc.setDblFn=fn_sdbl; sc.sendDir=fn_sd;
        sc.port=serve_port; sc.token=serve_token;
        w=getenv("WAITSEC"); sc.waitSec=(w&&*w)?atoi(w):120;
        if(sc.waitSec<=0) sc.waitSec=120;
        fflush(stderr);
        return serve_loop(&sc);
    }

    /* 模型加载后暂停(供 gdb 检查内存张量表)。用 GDB_PAUSE 环境变量控制。
     * 触发 brk 断点指令, qemu -g 下 gdb 会停在此处, 检查后 continue 恢复。 */
    if(getenv("GDB_PAUSE")){
        fprintf(stderr,"[+] GDB_PAUSE: 扫描进程内存找权重区 (PID=%ld)\n",(long)getpid());
        fflush(stderr);
        /* 扫描 /proc/self/maps, dump 含 netG 或大量非零 float 权重的可读区 */
        FILE* mf=fopen("/proc/self/maps","r");
        if(mf){
            char line[512];
            int idx=0;
            while(fgets(line,sizeof line,mf)){
                unsigned long long a,b; char perm[8]={0};
                char path[256]={0};
                int n_parsed = sscanf(line,"%llx-%llx %7s %*s %*s %*s %255s", &a,&b,perm,path);
                if(n_parsed<3) continue;
                if(perm[0]!='r') continue;
                /* 跳过 .so/.apk/.jar 文件映射(代码段), 但保留 .dat (权重可能 mmap) */
                if(strstr(path,".so")||strstr(path,".apk")||strstr(path,".jar")||strstr(path,".dex")
                   ||strstr(path,".ttf")) continue;
                /* 超大区: 按 8MB 块采样, 只 dump 高权重密度块 (含大量合理 float) */
                if(b-a > (1ULL<<28)){
                    unsigned long long block = 8ULL<<20;  /* 8MB */
                    for(unsigned long long p=a; p<b; p+=block){
                        unsigned long long pe = p+block < b ? p+block : b;
                        /* 采样检查权重密度 */
                        int ws=0, tot=0;
                        for(unsigned long long q=p; q+4<pe; q+=16384){
                            float f; memcpy(&f,(void*)(uintptr_t)q,4);
                            tot++;
                            if(f==f && fabsf(f)>1e-8 && fabsf(f)<10) ws++;
                        }
                        if(tot && ws*100/tot > 30){  /* 高权重密度 */
                            char outpath[256];
                            snprintf(outpath,sizeof outpath,"/tmp/opencode/dump_%02d_%llx_%llx.bin",idx,p,pe);
                            FILE* wf=fopen(outpath,"wb");
                            if(wf){ fwrite((void*)(uintptr_t)p,1,pe-p,wf); fclose(wf);
                                fprintf(stderr,"[GDB_PAUSE] bigdump 0x%llx-0x%llx (%llu, ws=%d)\n",p,pe,pe-p,ws); }
                            idx++;
                        }
                    }
                    continue;
                }
                if(b-a < 4096) continue;
                /* dump 所有匿名/堆可读区(私有+共享), 限制总大小 */
                /* 采样判断(仅用于报告): 是否含 netG 或高密度权重 */
                int has_netg=0, weight_score=0, total_score=0;
                unsigned long long step = 8192;
                for(unsigned long long p=a; p+4<b; p+=step){
                    unsigned char m[4]={0};
                    memcpy(m,(void*)(uintptr_t)p,4);
                    total_score++;
                    if(m[0]==0x6e&&m[1]==0x65&&m[2]==0x74&&m[3]==0x47){ has_netg=1; }
                    float f;
                    memcpy(&f,m,4);
                    if(f==f && fabsf(f)>1e-8 && fabsf(f)<10) weight_score++;
                }
                /* dump 所有匿名可读区(忽略纯代码/零填充小片) */
                char outpath[256];
                snprintf(outpath,sizeof outpath,"/tmp/opencode/dump_%02d_%llx_%llx.bin",idx,a,b);
                FILE* wf=fopen(outpath,"wb");
                if(wf){
                    unsigned long long wrote=0;
                    while(wrote<b-a){
                        size_t chunk = (size_t)((b-a-wrote)< (1ULL<<20) ? (b-a-wrote) : (1ULL<<20));
                        size_t w=fwrite((void*)(uintptr_t)(a+wrote),1,chunk,wf);
                        if(w!=chunk) break;
                        wrote+=w;
                    }
                    fclose(wf);
                    fprintf(stderr,"[GDB_PAUSE] dumped 0x%llx-0x%llx (%llu bytes, netg=%d ws=%d) -> %s\n",
                            a,b,b-a,has_netg,weight_score,outpath);
                }
                idx++;
            }
            fclose(mf);
        }
        fprintf(stderr,"[+] GDB_PAUSE dump 完成\n");
        fflush(stderr);
        /* brk 断点: 仅当 GDB_ATTACH=1 (qemu -g + gdb) 时触发 */
        if(getenv("GDB_ATTACH")){
            /* 先等 cronet 崩溃线程被 crash_handler 处理退出 (避免 gdb 被 SIGSEGV 干扰) */
            fprintf(stderr,"[+] GDB_ATTACH: 等 cronet 线程退出 (8s)...\n"); fflush(stderr);
            for(int w=0; w<80; w++){ usleep(100000); }
            fprintf(stderr,"[+] GDB_ATTACH: brk 断点\n"); fflush(stderr);
#if defined(__aarch64__)
            __asm__ volatile("brk #0x1");
#else
            __asm__ volatile("bkpt #1");   /* ARMv7 的等价调试陷阱 */
#endif
        }
        fprintf(stderr,"[+] GDB_PAUSE 结束\n");
        fflush(stderr);
    }

    /* 4) 合成 */
    if(do_synth){
        g_out=fopen(out,"wb"); if(!g_out){ fprintf(stderr,"无法打开 %s\n",out); return 11; }
        setStr(penv,clazz,fn_ss,handle,"tts_text",text);
        { const char *sp=getenv("TTS_SPEED");
          const char *sil=getenv("TTS_SIL");
          setInt (penv,clazz,fn_si,handle,"tts_speed", sp?atoi(sp):10);
          if(sil) setInt(penv,clazz,fn_si,handle,"tts_silence_duration",atoi(sil));
          const char *ssl=getenv("TTS_SUBLEN");
          if(ssl) setInt(penv,clazz,fn_si,handle,"tts_sub_sentence_length",atoi(ssl));
          const char *sr=getenv("TTS_SPEED_RATIO");
          if(sr) setInt(penv,clazz,fn_si,handle,"tts_speed_ratio",atoi(sr)); }
        setInt (penv,clazz,fn_si,handle,"tts_volume",100);
        fprintf(stderr,"[+] sendDirective(1000 START_ENGINE) ...\n");
        jobject jd0=new_str("");
        jint rs0=fn_sd(penv,clazz,handle,1000,jd0);
        fprintf(stderr,"[+] sendDirective(1000) ret=%d\n",(int)rs0);
        fprintf(stderr,"[+] sendDirective(1400 SYNTHESIS) ...\n");
        jobject jd=new_str("");
        jint rs=fn_sd(penv,clazz,handle,1400,jd);
        fprintf(stderr,"[+] sendDirective ret=%d, PCM total=%ld bytes\n",(int)rs,g_total);
        /* GDB_SYNTH: 合成指令已提交, 引擎将异步推理。触发 brk 供 gdb 断点 FusedConv 内核。 */
        if(getenv("GDB_SYNTH")){
            fprintf(stderr,"[+] GDB_SYNTH: 合成开始, brk 断点 (gdb 设置 FusedConv 断点后 continue)\n");
            fflush(stderr);
#if defined(__aarch64__)
            __asm__ volatile("brk #0x1");
#else
            __asm__ volatile("bkpt #1");   /* ARMv7 的等价调试陷阱 */
#endif
        }
        /* MEL_DUMP: 捕获 melgan 推理前/中的内存快照 (无 gdb 交互)。
         * 先 dump (melgan 输入前), 再等首个 PCM 块 (melgan 处理中), 再 dump。 */
        if(getenv("MEL_DUMP")){
            fprintf(stderr,"[+] MEL_DUMP: 合成开始, dump #0 (melgan 前)\n");
            fflush(stderr);
            melfwd_dump();
            fprintf(stderr,"[+] MEL_DUMP: 等首个 PCM 块 (melgan 处理中)...\n");
            fflush(stderr);
            for(int s=0;s<200;s++){ usleep(100000); if(g_total>0) break; }
            g_melfwd_dumped=0;
            fprintf(stderr,"[+] MEL_DUMP: dump #1 (推理中) g_total=%ld\n",(long)g_total);
            fflush(stderr);
            melfwd_dump();
        }
        /* ★上限改成 env WAITSEC（缺省 600s＝旧行为一字不变）：多句／长句整发在 qemu 下会超 600s、
           旧版写死会让 out.pcm 被静默截半（第 246 发·补二百五十二：3 句那发截在 245760／300480 B）。
           ★解析必须**在文案之前**：第一版把 `fprintf` 留在上面・于是 `WAITSEC=60` 那档真的截了、
             文案却仍写「最长 600s」＝正控假红（把"env 没接上"读反・真截断的是另一回事）。 */
        { const char *wsv = getenv("WAITSEC"); g_waitsec = (wsv && *wsv) ? atoi(wsv) : 600;
          if (g_waitsec <= 0) g_waitsec = 600; }
        fprintf(stderr,"[+] 等待合成回调 (最长 %ds, 收到 1409 结束标记即停) ...\n", g_waitsec);
        for(int w=0;w<g_waitsec*10;w++){
            usleep(100000);
            fprintf(stderr,"[wait] %.1fs total=%ld end=%d\n",(w+1)*0.1,(long)g_total,g_end_marker);
            if(g_end_marker) break;
        }
        fflush(g_out); fclose(g_out);
        if(getenv("MELEND_DUMP")) melfwd_dump_end();
        /* GDB_END: 合成完成后暂停 (melgan 全部块已推理, 张量在内存)。
         * 供 gdb 断点后 dump 整个可写内存查找 predictor/combine 张量。 */
        if(getenv("GDB_END")){
            fprintf(stderr,"[+] GDB_END: 合成完成, brk 断点\n");
            fflush(stderr);
#if defined(__aarch64__)
            __asm__ volatile("brk #0x1");
#else
            __asm__ volatile("bkpt #1");   /* ARMv7 的等价调试陷阱 */
#endif
        }
        fprintf(stderr,"[+] 合成结束: PCM total=%ld bytes, end=%d\n",(long)g_total,g_end_marker);
        /* 避免 destroyEngineToNative 卡死(引擎工作线程在 qemu 下无法优雅退出),
         * 直接收尾退出, PCM 已完整落盘。 */
        fflush(stderr);
        _exit(0);
    }

    /* 5) 释放 */
    if(fn_de) fn_de(penv,clazz,handle);
    fprintf(stderr,"[+] done\n");
    return 0;
}
