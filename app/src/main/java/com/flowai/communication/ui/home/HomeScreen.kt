package com.flowai.communication.ui.home
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flowai.communication.data.repository.DemoConversations
import com.flowai.communication.ui.components.*

@Composable fun HomeScreen(open: (String?) -> Unit, clearedNotice: String?) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("让沟通有下一步", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("把一段聊天粘进来，先看懂沟通状态，再决定怎么回、怎么做。", style = MaterialTheme.typography.bodyLarge)
        }
        item { MockNote() }
        clearedNotice?.let { message -> item { InfoCard(message, listOf("原文、分析与回复已从本次会话移除。你主动复制的内容仍在剪贴板。")) } }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("从这里开始", style = MaterialTheme.typography.titleLarge)
                    Text("粘贴一段聊天，查看沟通状态和三个建议。")
                    Button(onClick = { open(null) }, modifier = Modifier.fillMaxWidth()) { Text("粘贴聊天文本") }
                }
            }
        }
        item {
            Text("不知道怎么开始？先点一个示例试试", style = MaterialTheme.typography.titleMedium)
            Text("示例会一键载入一段写好的聊天，照着点一遍就懂了。", style = MaterialTheme.typography.bodySmall)
        }
        item { OutlinedButton(onClick = { open(DemoConversations.A) }, modifier = Modifier.fillMaxWidth()) { Text("Demo A · 任务进度与回复") } }
        item { OutlinedButton(onClick = { open(DemoConversations.B) }, modifier = Modifier.fillMaxWidth()) { Text("Demo B · 组会与任务提取") } }
        item {
            InfoCard("三步走", listOf(
                "1. 粘贴一段聊天（每行一条消息）",
                "2. 查看沟通状态和三个建议",
                "3. 选一个建议，得到回复或任务 / 事件"
            ))
        }
        item {
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("导入聊天截图 · 后续开放") }
            Text("第一阶段仅支持文本，截图识别尚未接入。", style = MaterialTheme.typography.bodySmall)
        }
        item { InfoCard("内容只用于本次会话", listOf(
            "结束或返回首页后清除，不保留最近分析。",
            "切到后台时，会话可暂留内存供继续操作；请在完成后主动结束。",
            "应用进程重建后需要重新导入聊天。"
        )) }
    }
}
