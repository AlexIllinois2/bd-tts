package com.bd.tts.audiobook

import android.content.Context

/** 听书专用的轻量偏好：上次打开的书、每本书记住的行号（0 基）。 */
object BookPrefs {
    private const val FILE = "bd_audiobook"
    private const val LAST_BOOK = "last_book"

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun lastBook(c: Context): String = sp(c).getString(LAST_BOOK, "") ?: ""

    fun setLastBook(c: Context, id: String) {
        sp(c).edit().putString(LAST_BOOK, id).apply()
    }

    fun position(c: Context, id: String): Int =
        maxOf(0, sp(c).getInt("pos_$id", 0))

    /** 位置记忆是固定行为（像普通播放器一样）；想重听从第 1 行开始，跳行到 1 即可。 */
    fun savePosition(c: Context, id: String, line: Int, total: Int) {
        if (id.isBlank()) return
        val clamped = line.coerceIn(0, maxOf(0, total - 1))
        val key = "pos_$id"
        if (sp(c).getInt(key, -1) != clamped) {
            sp(c).edit().putInt(key, clamped).apply()
        }
    }

    fun clearPosition(c: Context, id: String) {
        sp(c).edit().remove("pos_$id").apply()
    }
}
