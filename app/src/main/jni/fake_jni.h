#ifndef MIDU_FAKE_JNI_H
#define MIDU_FAKE_JNI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 纯 C 接口：本头文件被 JNI 层引用，绝不能出现 JNI 类型。 */

/* 通用选项类型（与 Java EngineOption.Type 一一对应）：
 *   0 = string  1 = int  2 = bool  3 = double
 * 值一律用字符串传递，C 层用 atoi/atof 转换，避免 JNI 层构造结构体数组。 */
enum {
    MIDU_OPT_STRING = 0,
    MIDU_OPT_INT    = 1,
    MIDU_OPT_BOOL   = 2,
    MIDU_OPT_DOUBLE = 3
};

void*        midu_engine_load(const char* lib_dir, const char* res_root,
                              const char* voice, const char* base,
                              int sample_rate, char* err, int err_cap);

/* 固定三参数版（保留给旧调用点），内部转调 synth_ex 并传空选项表。 */
int          midu_engine_synth(void* handle, const char* text,
                               int speed, int pitch, int volume, int timeout_ms);

/* 通用版：在基础 speed/pitch/volume 之后，按序下发任意 tts_* 选项。
 * keys / vals / types 三条平行数组，长度 n。
 * 会过滤掉一组「钉死的初始化档位」，防止 UI 误下发破坏 PCM 回调链路。 */
int          midu_engine_synth_ex(void* handle, const char* text,
                                  int speed, int pitch, int volume, int timeout_ms,
                                  const char* const* keys,
                                  const char* const* vals,
                                  const int* types,
                                  int n);

const uint8_t* midu_engine_pcm(void* handle, int* len);
void         midu_engine_stop(void* handle);
void         midu_engine_destroy(void* handle);
const char*  midu_engine_version(void);
const char*  midu_engine_last_error(void);

#ifdef __cplusplus
}
#endif
#endif
