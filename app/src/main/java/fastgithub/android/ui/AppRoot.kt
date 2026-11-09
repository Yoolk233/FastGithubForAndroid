package fastgithub.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fastgithub.android.AppState
import fastgithub.android.Config
import kotlinx.coroutines.launch

enum class FastTab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Filled.Home),
    Nodes("节点", Icons.AutoMirrored.Filled.List),
    Mirror("下载加速", Icons.Filled.Share),
    Settings("设置", Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    onToggle: (Boolean) -> Unit,
    onReprobe: () -> Unit,
    onOpenVpnSettings: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(FastTab.Home) }

    val running by AppState.running.collectAsState()
    val busy by AppState.busy.collectAsState()
    val nodes by AppState.nodes.collectAsState()
    val mapping by AppState.mapping.collectAsState()
    val status by AppState.status.collectAsState()
    val detail by AppState.detail.collectAsState()
    val phase by AppState.phase.collectAsState()
    val logs by AppState.logs.collectAsState()
    val lastProbeAt by AppState.lastProbeAt.collectAsState()
    val stale by AppState.staleNodes.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { msg ->
        scope.launch { snackbar.showSnackbar(msg, withDismissAction = true, duration = SnackbarDuration.Short) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 两个 Text 直接放进 title 槽是各自叠在同一处，必须包一层 Row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "FastGithub",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 19.sp
                        )
                        Text(
                            "  v${Config.Version}",
                            fontSize = 11.sp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (running) {
                        StatusPill(
                            if (busy) "优选节点中" else "加速中",
                            statusColor(if (busy) "WEAK" else "GOOD"),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar {
                FastTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, fontSize = 12.sp) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                FastTab.Home -> HomeScreen(
                    running = running,
                    busy = busy,
                    phase = phase,
                    status = status,
                    detail = detail,
                    nodes = nodes,
                    usable = mapping.size,
                    total = Config.domains.size,
                    lastProbeAt = lastProbeAt,
                    onToggle = onToggle,
                    onReprobe = onReprobe,
                    onGoMirror = { tab = FastTab.Mirror }
                )

                FastTab.Nodes -> NodesScreen(
                    nodes = nodes,
                    usable = mapping.size,
                    total = Config.domains.size,
                    busy = busy,
                    running = running,
                    stale = stale,
                    onReprobe = onReprobe
                )

                FastTab.Mirror -> MirrorScreen(onNotify = notify)

                FastTab.Settings -> SettingsScreen(
                    running = running,
                    usable = mapping.size,
                    total = Config.domains.size,
                    logs = logs,
                    onOpenVpnSettings = onOpenVpnSettings,
                    onStop = { onToggle(false) },
                    onNotify = notify
                )
            }
        }
    }
}
