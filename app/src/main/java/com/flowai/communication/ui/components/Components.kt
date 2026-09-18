package com.flowai.communication.ui.components
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val colors = lightColorScheme(
    primary = Color(0xFF315CD5), onPrimary = Color.White,
    background = Color(0xFFF6F8FC), surface = Color.White,
    onSurface = Color(0xFF17233A), onBackground = Color(0xFF17233A),
    secondaryContainer = Color(0xFFE7EEFF), onSecondaryContainer = Color(0xFF274595))
@Composable fun FlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
@Composable fun InfoCard(title: String, lines: List<String>, emptyText: String = "暂未识别到明确内容") {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            (lines.ifEmpty { listOf(emptyText) }).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
@Composable fun MockNote(text: String = "本地 Mock 演示 · 无需 API Key · 不上传聊天内容") {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodySmall)
    }
}
