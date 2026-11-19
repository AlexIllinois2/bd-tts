package com.bd.tts.audiobook

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 播放服务与听书页共享的 UI 状态（同进程内直接观察，不需要 AIDL）。 */
data class PlayerUiState(
    val bookId: String = "",
    val bookName: String = "",
    val line: Int = 0,              // 0 基行号
    val lineCount: Int = 0,
    val sentence: Int = 0,          // 当前行内的 0 基句号（一行按句末标点切分）
    val sentenceCount: Int = 0,     // 当前行的句子数
    val fraction: Float = 0f,       // 当前行内综合播放比例 0..1 = (句号 + 句内比例) / 句数
    val playing: Boolean = false,
    val preparing: Boolean = false, // 正在合成当前句（host2 首次加载可能要几十秒）
    val message: String = ""
)

object AudiobookState {
    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    fun update(block: (PlayerUiState) -> PlayerUiState) {
        _ui.value = block(_ui.value)
    }
}
