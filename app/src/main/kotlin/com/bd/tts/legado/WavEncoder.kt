package com.bd.tts.legado

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把引擎给出的 s16le / 24 kHz / 单声道 PCM 包成 WAV。
 * Legado 的 HttpTTS 要求响应是音频二进制流（audio/wav 或 audio/mpeg），
 * 不能返回 JSON / text，所以必须在这里补 44 字节 RIFF 头。
 */
object WavEncoder {
    fun wrap(pcm: ByteArray, sampleRate: Int = 24000, channels: Int = 1, bits: Int = 16): ByteArray {
        val out = ByteArray(44 + pcm.size)
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(36 + pcm.size)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)                                      // fmt chunk size
        b.putShort(1)                                     // PCM
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * channels * bits / 8)        // byte rate
        b.putShort((channels * bits / 8).toShort())       // block align
        b.putShort(bits.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(pcm.size)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }
}
