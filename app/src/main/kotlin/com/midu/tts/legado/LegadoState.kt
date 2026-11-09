package com.midu.tts.legado

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 服务进程与 Compose UI 在同一进程，用这一份状态直接互通。所有写入都切到主线程。 */
object LegadoState {
    val logs = mutableStateListOf<String>()
    var running by mutableStateOf(false)
        private set
    var address by mutableStateOf("—")
        private set
    var lastRequest by mutableStateOf("—")
        private set
    var lastError by mutableStateOf("")
        private set
    var requestCount by mutableStateOf(0)
        private set
    var synthCount by mutableStateOf(0)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun log(s: String) = onMain {
        logs.add(0, "${fmt.format(Date())}  $s")
        if (logs.size > 100) logs.removeAt(logs.size - 1)
    }

    fun markStarted(addr: String) = onMain { running = true; address = addr; lastError = "" }
    fun markStopped() = onMain { running = false; address = "—" }
    fun markError(e: String) = onMain { lastError = e; log("× $e") }
    fun markRequest(path: String) = onMain { lastRequest = path; requestCount++ }
    fun markSynth(bytes: Int) = onMain { synthCount++; log("✓ 合成完成 $bytes B WAV") }
}
