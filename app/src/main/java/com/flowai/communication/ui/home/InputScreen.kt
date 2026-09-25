package com.flowai.communication.ui.home
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flowai.communication.data.model.SourceType
import com.flowai.communication.ui.components.InfoCard
import com.flowai.communication.ui.components.EngineNote

@Composable fun InputScreen(
    text: String,
    error: String?,
    edit: (String) -> Unit,
    analyze: () -> Unit,
    source: SourceType = SourceType.TEXT,
    supersededNotice: String? = null,
    clearedNotice: String? = null,
    replyStyle: com.flowai.communication.ai.ReplyStyle = com.flowai.communication.ai.ReplyStyle.WARM,
    onSelectReplyStyle: (com.flowai.communication.ai.ReplyStyle) -> Unit = {}
) {
    // The action sits below the scrollable content rather than inside it. With the keyboard open the
    // visible area shrinks a lot, and a button at the end of the scrolled content ends up behind the
    // keyboard — reachable only by scrolling, which is not something a user thinks to do.
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("粘贴聊天文本", style = MaterialTheme.typography.headlineMedium)
            Text("每行一条消息，推荐使用“我：…”和“对方：…”。无说话人标签的行会标记为未知。")
            EngineNote()
            ReplyStyleField(replyStyle, onSelectReplyStyle)
            supersededNotice?.let { InfoCard(it, listOf("新内容已载入，上面那段分析不再保留。")) }
            clearedNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            val sourceNotice = when (source) {
                SourceType.SHARE -> "内容来自系统分享"
                SourceType.PROCESS_TEXT -> "内容来自划词选择"
                SourceType.SCREENSHOT -> "内容来自截屏识别"
                else -> null
            }
            sourceNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            OutlinedTextField(value = text, onValueChange = edit, modifier = Modifier.fillMaxWidth(),
                minLines = 8, maxLines = 14, label = { Text("聊天内容") },
                supportingText = { Text("${text.length} / 20,000") }, isError = error != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Button(
                    onClick = analyze,
                    enabled = text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("分析当前沟通") }
                if (text.isBlank()) {
                    Text(
                        "输入框为空：粘贴或选择一个示例后再分析",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Persona picker for the drafts the engine writes.
 *
 * A read-only field with an attached menu rather than free text: the choices are a fixed set of
 * voices, and the supporting line states where the choice actually bites — the local simulator has
 * no prompt to replace, so claiming otherwise would overpromise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReplyStyleField(
    current: com.flowai.communication.ai.ReplyStyle,
    onSelect: (com.flowai.communication.ai.ReplyStyle) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("AI 角色风格") },
            supportingText = { Text("切换后向分析服务发送的系统提示词会替换为该角色设定") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            com.flowai.communication.ai.ReplyStyle.entries.forEach { style ->
                DropdownMenuItem(
                    text = { Text(style.label) },
                    onClick = { onSelect(style); expanded = false }
                )
            }
        }
    }
}
