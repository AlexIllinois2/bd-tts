package com.bd.tts.audiobook

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 播放服务与听书页共享的 UI 状态（同进程内直接观察，不需要 AIDL）。 */
data class PlayerUiState(
    val bookId: String = "",
    val bookName: String = "",
    val line: Int = 0,               // 当前朗读行的 0 基行号
    val lineCount: Int = 0,          // 总行数（<=0 表示未知 / 统计中）
    val byteOffset: Long = 0L,       // 当前行首字节偏移（内部定位用，界面不显示）
    val sizeBytes: Long = 0L,        // 文件字节数（仅用于显示 KB）
    val sentence: Int = 0,           // 当前行内的 0 基句号（一行按句末标点切分）
    val sentenceCount: Int = 0,      // 当前行的句子数
    val fraction: Float = 0f,        // 全书行进度 0..1 = (行号 + 行内比例) / 总行数
    val lineText: String = "",       // 当前行的显示文本（去注音），供界面按句展示
    val playing: Boolean = false,
    val preparing: Boolean = false,  // 正在合成当前句（host2 首次加载可能要几十秒）
    val sleepRemainingMs: Long = 0L, // 定时剩余毫秒；0 = 未设定时
    val message: String = ""
)

object AudiobookState {
    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    fun update(block: (PlayerUiState) -> PlayerUiState) {
        _ui.value = block(_ui.value)
    }
}
