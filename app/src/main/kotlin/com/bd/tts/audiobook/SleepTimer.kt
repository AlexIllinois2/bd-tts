package com.bd.tts.audiobook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 定时停止播放：按真实时间倒计时，单例存活，切 Tab / 息屏 / 页面重建都不影响。
 * 听书页「定时」按钮设置分钟数；倒计时结束自动停止播放并清除定时状态。
 */
object SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    private var endAt = 0L
    private var app: Context? = null

    private val tick = object : Runnable {
        override fun run() {
            val remaining = endAt - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                endAt = 0L
                publish(0L)
                if (AudiobookState.ui.value.bookId.isNotBlank()) app?.let { AudiobookService.stop(it) }
                AudiobookState.update { it.copy(message = "定时结束，已停止播放") }
                return
            }
            publish(remaining)
            handler.postDelayed(this, 1000L)
        }
    }

    private fun publish(remaining: Long) {
        AudiobookState.update { it.copy(sleepRemainingMs = remaining) }
    }

    /** 开始定时；分钟数会记住，下次弹窗自动填入。重复设置会重置倒计时。 */
    fun start(c: Context, minutes: Int) {
        val mins = maxOf(1, minutes)
        BookPrefs.setSleepMinutes(c, mins)
        app = c.applicationContext
        handler.removeCallbacks(tick)
        endAt = SystemClock.elapsedRealtime() + mins * 60_000L
        publish(endAt - SystemClock.elapsedRealtime())
        handler.post(tick)
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        endAt = 0L
        publish(0L)
    }

    /** 剩余毫秒；0 表示没有定时。 */
    fun remainingMs(): Long =
        if (endAt <= 0L) 0L else maxOf(0L, endAt - SystemClock.elapsedRealtime())

    /** 按钮上的倒计时文本：mm:ss（超过 99 分钟时分钟数照常显示）。 */
    fun format(ms: Long): String {
        val sec = (ms + 999L) / 1000L
        return "${sec / 60L}:${(sec % 60L).toString().padStart(2, '0')}"
    }
}
