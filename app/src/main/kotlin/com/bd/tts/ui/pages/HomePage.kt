package com.bd.tts.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bd.tts.ui.ActionButton
import com.bd.tts.ui.Actions
import com.bd.tts.ui.SectionLabel

@Composable
fun HomePage(refresh: Int, toast: (String) -> Unit) {
    val ctx = LocalContext.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionLabel("快捷操作")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("系统 TTS", Icons.Default.Settings,
                    { Actions.openSystemTts(ctx) }, Modifier.weight(1f))
                ActionButton("经典设置", Icons.Default.Info,
                    { Actions.openLegacy(ctx) }, Modifier.weight(1f))
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
