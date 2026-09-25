package com.flowai.communication.ui.action
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.flowai.communication.data.model.*
import com.flowai.communication.ui.components.*

@Composable fun ActionScreen(action: NextAction, result: ActionResult, editReply: (String, String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().imePadding().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(action.title, style = MaterialTheme.typography.headlineMedium) }
        item { EngineNote(result.note) }
        if (result.replies.isNotEmpty()) item { Text("推荐回复 · 可编辑后复制", style = MaterialTheme.typography.titleMedium) }
        result.replies.forEach { reply -> item(key = reply.style) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(reply.style, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = reply.text, onValueChange = { editReply(reply.style, it); copied = false }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { clipboard.setText(AnnotatedString(reply.text)); copied = true }) { Text("复制这条回复") }
                }
            }
        } }
        if (result.objects.isNotEmpty()) item {
            Text("Chat-to-Action", style = MaterialTheme.typography.titleLarge)
            Text("识别到 ${result.objects.count { it is ActionObject.Event }} 个事件 · ${result.objects.count { it is ActionObject.Task }} 个任务")
        }
        result.objects.forEach { obj -> item {
            when (obj) {
                is ActionObject.Task -> InfoCard("任务 · ${obj.title}", listOf("负责人：${obj.assignee ?: "待确认"}", "截止：${obj.deadline ?: "待确认"}", obj.detail ?: ""))
                is ActionObject.Event -> EventCalendarCard(obj)
                is ActionObject.Decision -> InfoCard("决定", listOf(obj.content))
            }
        } }
        if (result.objects.isNotEmpty()) item {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(result.objects.joinToString("\n") { formatObject(it) })); copied = true }, modifier = Modifier.fillMaxWidth()) { Text("复制事项清单") }
        }
        if (copied) item { Text("已复制到剪贴板", color = MaterialTheme.colorScheme.primary) }
        if (result.objects.isEmpty() && result.replies.isEmpty()) item { InfoCard("暂无可提取事项", listOf("这段聊天里没有识别到任务或事件。")) }
    }
}
private fun formatObject(obj: ActionObject): String = when(obj) {
    is ActionObject.Task -> "任务：${obj.title}｜负责人：${obj.assignee ?: "待确认"}｜截止：${obj.deadline ?: "待确认"}｜${obj.detail.orEmpty()}"
    is ActionObject.Event -> "事件：${obj.title}｜${obj.time ?: "待确认"}｜${obj.location ?: "待确认"}｜${obj.participants.joinToString("、")}"
    is ActionObject.Decision -> "决定：${obj.content}"
}
