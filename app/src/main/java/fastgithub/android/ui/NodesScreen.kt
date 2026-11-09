package fastgithub.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fastgithub.android.Config
import fastgithub.android.Node

private const val F_ALL = "all"
private const val F_OK = "ok"
private const val F_BAD = "bad"

@Composable
fun NodesScreen(
    nodes: List<Node>,
    usable: Int,
    total: Int,
    busy: Boolean,
    running: Boolean,
    stale: Boolean,
    onReprobe: () -> Unit
) {
    var filter by rememberSaveable { mutableStateOf(F_ALL) }

    val okCount = nodes.count { it.usable }
    val badCount = nodes.count { it.state == "BAD" || it.state == "FAIL" }
    val shown = when (filter) {
        F_OK -> nodes.filter { it.usable }
        F_BAD -> nodes.filter { it.state == "BAD" || it.state == "FAIL" }
        else -> nodes
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "已接管 $usable/$total 个域名",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            when {
                                busy -> "正在逐个测速，大约半分钟"
                                !running -> "加速未开启，下面是上次的测速结果"
                                stale -> "上次会话的结果，未在加速"
                                else -> "每个域名对应一个实测最快的边缘 IP"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onReprobe,
                        enabled = running && !busy
                    ) {
                        Text(if (busy) "测速中" else "重新测速", fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { if (total <= 0) 0f else usable.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().height(5.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter == F_ALL,
                onClick = { filter = F_ALL },
                label = { Text("全部 ${nodes.size}", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors()
            )
            FilterChip(
                selected = filter == F_OK,
                onClick = { filter = F_OK },
                label = { Text("可用 $okCount", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors()
            )
            FilterChip(
                selected = filter == F_BAD,
                onClick = { filter = F_BAD },
                label = { Text("失败 $badCount", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors()
            )
        }

        Spacer(Modifier.height(6.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.host }) { n ->
                NodeRow(n)
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "共 ${Config.domains.size} 个 GitHub 域名；「失败」表示当前没有探测到可用节点，不代表加速整体失效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun NodeRow(n: Node) {
    val color = statusColor(n.state)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(Modifier.size(8.dp), shape = CircleShape, color = color) {}
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    n.host,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    n.ip.ifEmpty { "未分配" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    n.shortState,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    n.msText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
