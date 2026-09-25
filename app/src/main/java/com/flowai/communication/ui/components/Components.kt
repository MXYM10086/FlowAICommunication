package com.flowai.communication.ui.components
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Single source of truth for the app's look: one blue family for brand and actions, cool grey
 * surfaces for content, and a light container tone per speaker side so chat bubbles stay readable.
 * The same values are mirrored in res/values/colors.xml for the XML-driven system theme.
 */
private val colors = lightColorScheme(
    primary = Color(0xFF315CD5), onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE7FF), onPrimaryContainer = Color(0xFF14275C),
    secondaryContainer = Color(0xFFE7EEFF), onSecondaryContainer = Color(0xFF274595),
    background = Color(0xFFF6F8FC), onBackground = Color(0xFF17233A),
    surface = Color.White, onSurface = Color(0xFF17233A),
    surfaceVariant = Color(0xFFE6EBF4), onSurfaceVariant = Color(0xFF46536D),
    outline = Color(0xFFC5CFDF),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B))
// One corner family for cards, fields and bubbles so nothing looks bolted on.
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp))
// Type ramp tuned for Chinese body text: generous line height, sizes that stay legible at a glance.
private val typography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp))
@Composable fun FlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}

/**
 * One chat message as a bubble: my side hugs the end edge on the primary container tone, the other
 * side hugs the start edge on a neutral tone, each with a clipped tail corner. Width is capped so
 * long messages keep a readable measure on tablets instead of stretching edge to edge.
 */
@Composable fun ChatBubble(label: String, text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.widthIn(max = 340.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomEnd = if (mine) 6.dp else 18.dp,
                bottomStart = if (mine) 18.dp else 6.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text, style = MaterialTheme.typography.bodyMedium,
                    color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
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
