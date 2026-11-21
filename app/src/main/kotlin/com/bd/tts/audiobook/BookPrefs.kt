package com.bd.tts.audiobook

import android.content.Context

/** 听书专用轻量偏好：上次打开的书、每本书记住的行号（0 基）、定时时长。 */
object BookPrefs {
    private const val FILE = "bd_audiobook"
    private const val LAST_BOOK = "last_book"
    private const val SLEEP_MINUTES = "sleep_minutes"
    // 换前缀：posb_ 存的是字节偏移(Long)，本版 posl_ 存行号(Int)。
    // 不换的话升级后会把旧字节偏移截断成行号，续播跳到错误位置。
    private const val POS_PREFIX = "posl_"

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun lastBook(c: Context): String = sp(c).getString(LAST_BOOK, "") ?: ""

    fun setLastBook(c: Context, id: String) {
        sp(c).edit().putString(LAST_BOOK, id).apply()
    }

    /** 续播位置（0 基行号）。 */
    fun position(c: Context, id: String): Int =
        maxOf(0, sp(c).getInt("$POS_PREFIX$id", 0))

    /** 位置记忆是固定行为；想重听从第 1 行开始，跳到第 1 行即可。 */
    fun savePosition(c: Context, id: String, line: Int, lineCount: Int) {
        if (id.isBlank() || lineCount <= 0) return
        val clamped = line.coerceIn(0, lineCount - 1)
        val key = "$POS_PREFIX$id"
        if (sp(c).getInt(key, -1) != clamped) {
            sp(c).edit().putInt(key, clamped).apply()
        }
    }

    fun clearPosition(c: Context, id: String) {
        sp(c).edit().remove("$POS_PREFIX$id").apply()
    }

    /** 定时时长（分钟）：记住上次输入，默认 30。 */
    fun sleepMinutes(c: Context): Int = maxOf(1, sp(c).getInt(SLEEP_MINUTES, 30))

    fun setSleepMinutes(c: Context, minutes: Int) {
        sp(c).edit().putInt(SLEEP_MINUTES, minutes).apply()
    }
}
