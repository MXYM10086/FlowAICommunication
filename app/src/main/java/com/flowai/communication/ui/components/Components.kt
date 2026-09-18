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
/**
 * Corner badge naming the engine actually in use.
 *
 * A fixed "MOCK" label would be wrong as soon as the API is switched on, which is exactly the kind
 * of stale claim that misleads a reviewer.
 */
@Composable fun EngineBadge() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = androidx.compose.runtime.remember {
        com.flowai.communication.ai.EngineSettingsStore(context).load()
    }
    Text(
        if (settings.canUseRemote) "API" else "本机",
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(end = 16.dp)
    )
}

/**
 * States where analysis happens, based on the actual configuration.
 *
 * Not a fixed "local only" banner: the app can be pointed at an analysis service, and claiming
 * text never leaves the phone after that would be false.
 */
@Composable fun EngineNote(text: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = androidx.compose.runtime.remember {
        com.flowai.communication.ai.EngineSettingsStore(context).load()
    }
    val resolved = text ?: when {
        !settings.isConfigured -> "本机分析 · 聊天内容不离开手机"
        !settings.hasConsent -> "已配置分析服务，但尚未同意上传 · 当前仍在本机分析"
        else -> "上传分析 · 你主动提供的内容会发送至已配置的服务"
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
        Text(resolved, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodySmall)
    }
}
