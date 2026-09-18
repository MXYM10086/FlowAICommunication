package com.flowai.communication.ui.home
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flowai.communication.data.model.SourceType
import com.flowai.communication.ui.components.MockNote

@Composable fun InputScreen(text: String, error: String?, edit: (String) -> Unit, analyze: () -> Unit, source: SourceType = SourceType.TEXT) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("粘贴聊天文本", style = MaterialTheme.typography.headlineMedium)
        Text("每行一条消息，推荐使用“我：…”和“对方：…”。无说话人标签的行会标记为未知。")
        MockNote("内置案例使用固定模拟结果；其他文本使用通用模板。")
        val sourceNotice = when (source) {
            SourceType.SHARE -> "内容来自系统分享"
            SourceType.PROCESS_TEXT -> "内容来自划词选择"
            else -> null
        }
        sourceNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        OutlinedTextField(value = text, onValueChange = edit, modifier = Modifier.fillMaxWidth(),
            minLines = 8, maxLines = 14, label = { Text("聊天内容") },
            supportingText = { Text("${text.length} / 20,000") }, isError = error != null)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = analyze, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("分析当前沟通") }
    }
}
