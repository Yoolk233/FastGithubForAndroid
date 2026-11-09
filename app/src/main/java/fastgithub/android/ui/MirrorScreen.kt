package fastgithub.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fastgithub.android.Config
import fastgithub.android.MirrorStore
import fastgithub.android.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SAMPLE = "https://github.com/git-for-windows/git/releases/latest"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MirrorScreen(onNotify: (String) -> Unit) {
    var prefix by remember { mutableStateOf(MirrorStore.load()) }
    var url by remember {
        mutableStateOf("https://github.com/torvalds/linux/archive/refs/heads/master.zip")
    }
    var result by remember { mutableStateOf("") }
    var tip by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)

    fun copy(text: String) {
        if (text.isEmpty()) return
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("url", text))
        onNotify("已复制到剪贴板")
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard(title = "镜像源前缀", subtitle = "从预设里选，或直接改上面的输入框") {
            OutlinedTextField(
                value = prefix,
                onValueChange = { prefix = it },
                singleLine = true,
                label = { Text("https://前缀/", fontSize = 12.sp) },
                textStyle = mono,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Config.mirrorPresets.forEach { p ->
                    val selected = MirrorStore.normalize(p) == MirrorStore.normalize(prefix)
                    FilterChip(
                        selected = selected,
                        onClick = { prefix = p },
                        label = {
                            Text(p.removePrefix("https://").trimEnd('/'), fontSize = 11.sp)
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        testing = true
                        tip = "测试中…"
                        val p = prefix
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { Net.probeMirror(p, SAMPLE) }
                            testing = false
                            tip = if (r.startsWith("ERR|")) {
                                val parts = r.split('|')
                                "不可用：" + parts.getOrElse(1) { "未知错误" }
                            } else {
                                val parts = r.split('|')
                                "可用 · HTTP ${parts.getOrElse(0) { "-" }} · ${parts.getOrElse(1) { "-" }} ms"
                            }
                        }
                    },
                    enabled = !testing
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("测速中", fontSize = 12.sp)
                    } else {
                        Text("测速", fontSize = 12.sp)
                    }
                }
                Button(
                    onClick = {
                        MirrorStore.save(prefix)
                        val n = MirrorStore.normalize(prefix)
                        tip = "已设为默认：$n"
                        onNotify("已设为默认镜像")
                    }
                ) {
                    Text("设为默认", fontSize = 12.sp)
                }
            }

            if (tip.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HintText(tip)
            }
        }

        SectionCard(title = "链接转换", subtitle = "仓库页 / Release 附件 / raw / archive 都支持") {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("GitHub 原始链接", fontSize = 12.sp) },
                textStyle = mono,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                maxLines = 3
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { result = MirrorStore.apply(prefix, url) }) {
                    Text("转换", fontSize = 12.sp)
                }
                OutlinedButton(onClick = { result = MirrorStore.strip(url) }) {
                    Text("还原", fontSize = 12.sp)
                }
                OutlinedButton(onClick = {
                    val txt = result.ifEmpty { MirrorStore.apply(prefix, url) }
                    result = txt
                    copy(txt)
                }) {
                    Text("复制", fontSize = 12.sp)
                }
            }
        }

        SectionCard(
            title = "转换结果",
            subtitle = "点一下结果也可以直接复制",
            trailing = {
                if (result.isNotEmpty()) {
                    Text(
                        "复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { copy(result) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 76.dp)
                    .clickable { copy(result) }
            ) {
                SelectionContainer {
                    Text(
                        result.ifEmpty { "点上面的「转换」生成加速地址" },
                        modifier = Modifier.padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = if (result.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HintText("转换后的地址粘进浏览器或下载工具即可；镜像随时可能失效，用「测速」确认再用。")
        }

        Spacer(Modifier.height(4.dp))
    }
}
