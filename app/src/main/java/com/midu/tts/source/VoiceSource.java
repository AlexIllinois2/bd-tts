package com.midu.tts.source;

import java.io.InputStream;

/**
 * 离线语音来源抽象。rel 是相对 midu 根目录的路径，例如：
 *   zh-cn/ptl.dat、zh-cn/ptl.idx、BV001_24k_streaming/ptl.dat、
 *   zh-cn/frontend/g2p/eng/additional_dict_path.txt、speech_license.licbag
 */
public interface VoiceSource {
    String id();
    String[] listVoices() throws Exception;
    InputStream open(String rel) throws Exception;
    /** 用于增量缓存判定；内容变了必须变化。 */
    String fingerprint(String rel) throws Exception;
    void close();
}
