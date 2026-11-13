package com.bd.tts.engine;

/** 流式合成的一道 PCM 数据；buf 可能被复用，只消费 [0, len)。 */
public interface PcmSink {
    void onPcm(byte[] buf, int len) throws Exception;
}
