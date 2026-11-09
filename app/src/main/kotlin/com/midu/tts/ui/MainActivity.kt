package com.midu.tts.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import com.midu.tts.ui.pages.HomePage
import com.midu.tts.ui.pages.LegadoPage
import com.midu.tts.ui.pages.RulesPage
import com.midu.tts.ui.pages.SoundPage
import com.midu.tts.ui.pages.SourcePage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 用户勾选「随应用启动自动拉起」时，从 Activity 启动前台服务（满足后台启动限制）。
        if (com.midu.tts.util.Prefs.get(this).httpAutoStart()) {
            com.midu.tts.legado.LegadoHttpService.start(this)
        }
        setContent { MiduTheme { AppScaffold() } }
    }
}

private data class Tab(val title: String, val short: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold() {
    val tabs = listOf(
        Tab("Midu TTS", "首页", Icons.Default.Home),
        Tab("声音调校", "声音", Icons.Default.Settings),
        Tab("发音规则", "规则", Icons.Default.List),
        Tab("Legado 对接", "Legado", Icons.Default.Share),
        Tab("语音源 · 诊断", "诊断", Icons.Default.Info)
    )
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
                0 -> HomePage(refresh, toast)
                1 -> SoundPage(refresh, toast)
                2 -> RulesPage(refresh, toast)
                3 -> LegadoPage(refresh, toast)
                else -> SourcePage(refresh, toast)
            }
        }
    }
}
