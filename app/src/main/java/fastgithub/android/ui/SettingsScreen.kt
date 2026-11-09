package fastgithub.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fastgithub.android.AppState
import fastgithub.android.Config

@Composable
fun SettingsScreen(
    running: Boolean,
    usable: Int,
    total: Int,
    logs: List<String>,
    onOpenVpnSettings: () -> Unit,
    onStop: () -> Unit,
    onNotify: (String) -> Unit
) {
    val ctx = LocalContext.current
    val logScroll = rememberScrollState()

    LaunchedEffect(logs.size) {
        if (logScroll.maxValue > 0) logScroll.scrollTo(logScroll.maxValue)
    }

    fun copyLogs() {
        if (logs.isEmpty()) {
            onNotify("还没有日志")
            return
        }
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("fastgithub-log", logs.joinToString("\n")))
        onNotify("日志已复制")
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard(title = "加速状态", subtitle = "状态来自正在运行的 VPN 服务，而不是界面自己记的") {
            KeyValueRow(
                "当前状态",
                if (running) "已开启" else "已关闭",
                if (running) statusColor("GOOD") else MaterialTheme.colorScheme.onSurfaceVariant
            )
            KeyValueRow("已接管域名", "$usable/$total")
            KeyValueRow("VPN 服务", if (running) "运行中（前台服务）" else "未运行")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenVpnSettings) {
                    Text("系统 VPN 设置", fontSize = 12.sp)
                }
                if (running) {
                    Button(onClick = onStop) {
                        Text("停止加速", fontSize = 12.sp)
                    }
                }
            }
        }

        SectionCard(
            title = "运行日志",
            subtitle = "最近 ${logs.size} 条",
            trailing = {
                Row {
                    TextButton(onClick = { copyLogs() }) {
                        Text("复制", fontSize = 12.sp)
                    }
                    TextButton(onClick = { AppState.logs.value = emptyList() }) {
                        Text("清空", fontSize = 12.sp)
                    }
                }
            }
        ) {
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .height(280.dp)
                        .verticalScroll(logScroll)
                        .padding(12.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            "暂无日志",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    logs.forEach { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        SectionCard(title = "常见问题") {
            Faq("加速没效果", "手机设置 → 网络 → 私人 DNS，必须保持「自动」或「关闭」。设成自定义主机名时，系统 DNS 不经过本机隧道。")
            Faq("提示建立 VPN 失败", "同一时间只能有一个 VPN，先关掉其它 VPN / 代理类应用再开。")
            Faq("部分域名显示失败", "该域名当前没探测到可用节点，属正常波动，点「重新测速」再试。")
            Faq("后台被杀", "系统清理后台会中断加速，重新打开 App 开启即可，上次结果有缓存，秒级生效。")
        }

        SectionCard(title = "关于") {
            KeyValueRow("版本", "v${Config.Version}（${Config.domains.size} 个域名）")
            KeyValueRow("加速方式", "VpnService 接管 DNS，不做全局代理")
            Spacer(Modifier.height(8.dp))
            HintText(
                "只改写发往 GitHub 相关域名的 DNS 应答，把域名解析到实测最快的边缘 IP；" +
                    "网页与下载流量仍走原网络，不经过任何服务器，不改证书、不装插件。"
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "FastGithub · Android",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Faq(q: String, a: String) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Text(
            q,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        HintText(a)
    }
}
