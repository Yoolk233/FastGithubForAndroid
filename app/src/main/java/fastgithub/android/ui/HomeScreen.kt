package fastgithub.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fastgithub.android.AppState
import fastgithub.android.Config
import fastgithub.android.Node
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

private fun avgMs(nodes: List<Node>): String {
    val list = nodes.filter { it.ms > 0 }.map { it.ms }
    if (list.isEmpty()) return "-"
    return "${list.average().toInt()} ms"
}

private fun lastProbe(ts: Long): String {
    if (ts <= 0) return "从未"
    return Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)
}

@Composable
fun HomeScreen(
    running: Boolean,
    busy: Boolean,
    phase: AppState.Phase,
    status: String,
    detail: String,
    nodes: List<Node>,
    usable: Int,
    total: Int,
    lastProbeAt: Long,
    onToggle: (Boolean) -> Unit,
    onReprobe: () -> Unit,
    onGoMirror: () -> Unit
) {
    val runningColor = statusColor("GOOD")
    val warnColor = statusColor("WEAK")
    val badColor = statusColor("BAD")
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant

    val pillText = when (phase) {
        AppState.Phase.IDLE -> "未加速"
        AppState.Phase.CONNECTING -> "建立隧道"
        AppState.Phase.PROBING -> "优选节点"
        AppState.Phase.READY -> "已生效"
        AppState.Phase.ERROR -> "有异常"
    }
    val pillColor = when (phase) {
        AppState.Phase.IDLE -> idleColor
        AppState.Phase.CONNECTING, AppState.Phase.PROBING -> warnColor
        AppState.Phase.READY -> runningColor
        AppState.Phase.ERROR -> badColor
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(pillText, pillColor.copy(alpha = 1f))
                    Spacer(Modifier.weight(1f))
                    Text(
                        "v${Config.Version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    lineHeight = 19.sp
                )

                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                    )
                }

                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    HeroStat("已接管", "$usable/$total", Modifier.weight(1f))
                    HeroStat("平均延迟", avgMs(nodes), Modifier.weight(1f))
                    HeroStat("上次测速", lastProbe(lastProbeAt), Modifier.weight(1f))
                }

                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "加速开关",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            when {
                                running -> "点右侧关闭，隧道立刻断开"
                                busy -> "正在准备，请稍候"
                                else -> "点右侧开启，浏览器与下载器全局生效"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                        )
                    }
                    Switch(
                        checked = running,
                        onCheckedChange = onToggle,
                        enabled = !busy || running
                    )
                }
            }
        }

        SectionCard(title = "常用操作") {
            ActionRow(
                title = "下载加速 · 镜像链接转换",
                subtitle = "把 GitHub 链接套上镜像前缀，绕过下载限速",
                onClick = onGoMirror
            )
            HorizontalDivider(
                Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            ActionRow(
                title = if (busy) "正在重新测速…" else "重新测速",
                subtitle = when {
                    busy -> "21 个域名，大约半分钟"
                    !running -> "需要先开启加速"
                    else -> "立即刷新 ${Config.domains.size} 个域名的优选结果"
                },
                enabled = running && !busy,
                onClick = onReprobe
            )
        }

        SectionCard(title = "使用提示") {
            HintText("· 手机「私人 DNS」必须保持「自动」或「关闭」；设成自定义主机名时系统 DNS 不经过本机隧道，加速会失效。")
            Spacer(Modifier.height(6.dp))
            HintText("· 本工具只用 VpnService 接管 DNS 查询，不代理、不转发任何网页与下载流量。")
            Spacer(Modifier.height(6.dp))
            HintText("· 同一时间只能有一个 VPN 运行，开了其它 VPN / 代理会建立失败。")
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "节点不可用属正常波动，点「重新测速」可再试。只要 github.com / api / raw / codeload 可用，网页与常规下载就已加速。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.62f)
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "›",
            fontSize = 18.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Transparent
        )
    }
}
