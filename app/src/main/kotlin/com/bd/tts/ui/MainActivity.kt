package com.bd.tts.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import com.bd.tts.ui.pages.PronunciationPage
import com.bd.tts.ui.pages.RulesPage
import com.bd.tts.ui.pages.SoundPage
import com.bd.tts.ui.pages.SourcePage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 用户勾选「随应用启动自动拉起」时，从 Activity 启动前台服务（满足后台启动限制）。
        if (com.bd.tts.util.Prefs.get(this).httpAutoStart()) {
            com.bd.tts.legado.LegadoHttpService.start(this)
        }
        setContent { BdTheme { AppScaffold() } }
    }
}

private data class Tab(val title: String, val short: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold() {
    val tabs = listOf(
        Tab("声音调校", "声音", Icons.Default.Settings),
        Tab("词语读音调整", "读音", Icons.Default.Edit),
        Tab("发音规则", "规则", Icons.Default.List),
        Tab("语音源 · 诊断", "诊断", Icons.Default.Info)
    )
    // 首页已移除，默认落在「声音」页（tabs[0]）。
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { msg -> scope.launch { snack.showSnackbar(msg) } }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(tabs[tab].title) },
                scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = { refresh++ }) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i; refresh++ },
                        icon = { Icon(t.icon, t.title) },
                        label = { Text(t.short, maxLines = 1) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> SoundPage(refresh, toast)
                1 -> PronunciationPage(refresh, toast)
                2 -> RulesPage(refresh, toast)
                else -> SourcePage(refresh, toast)
            }
        }
    }
}
