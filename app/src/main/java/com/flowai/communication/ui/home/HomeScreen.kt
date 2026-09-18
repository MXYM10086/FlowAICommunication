package com.flowai.communication.ui.home
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.flowai.communication.data.repository.DemoConversations
import com.flowai.communication.system.FloatingAssistantService
import com.flowai.communication.system.OverlayPermission
import com.flowai.communication.ui.components.*

@Composable fun HomeScreen(
    open: (String?) -> Unit,
    clearedNotice: String?,
    captureNotice: String? = null,
    captureInProgress: Boolean = false,
    onRequestCapture: () -> Unit = {}
) {
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
            val context = LocalContext.current
            // Recomputed on resume so returning from the system permission screen shows the new state.
            var granted by remember { mutableStateOf(OverlayPermission.isGranted(context)) }
            var running by remember { mutableStateOf(FloatingAssistantService.isRunning) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        granted = OverlayPermission.isGranted(context)
                        running = FloatingAssistantService.isRunning
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            InfoCard(
                "悬浮入口",
                listOf(
                    if (!granted) "需要先在系统设置里允许「显示在其他应用上层」。"
                    else if (running) "悬浮球已开启，可在其他应用上方随时点开 FlowAI。"
                    else "已获得权限，可以开启悬浮球。",
                    "悬浮球只作为入口，不会自动读取任何聊天内容。",
                    // Reinstalling the app clears this permission on some ROMs, which looks like the
                    // bubble silently vanished; say so instead of leaving the user guessing.
                    if (!granted) "提示：重新安装应用后该权限可能会被系统清除，需要重新授权。"
                    else ""
                ).filter { it.isNotEmpty() }
            )
            Spacer(Modifier.height(8.dp))
            if (!granted) {
                Button(
                    onClick = { OverlayPermission.request(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("去系统设置授权") }
            } else {
                Button(
                    onClick = {
                        if (running) {
                            FloatingAssistantService.stop(context)
                            running = false
                        } else {
                            running = FloatingAssistantService.start(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (running) "关闭悬浮球" else "开启悬浮球") }
            }
        }
        item {
            InfoCard("截屏识别（测试版）", listOf(
                "把聊天界面显示在屏幕上，点下面的按钮，FlowAI 会截屏并识别其中的文字。",
                "只识别这一次；识别完立即释放，不会持续截屏。",
                if (captureInProgress) "正在等待截屏授权…" else "系统会先询问是否允许截屏。"
            ))
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRequestCapture,
                enabled = !captureInProgress,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (captureInProgress) "正在截屏…" else "截屏识别聊天内容") }
            Text(
                "识别结果会进入同一个分析流程，可先核对再分析。",
                style = MaterialTheme.typography.bodySmall
            )
            captureNotice?.let {
                Spacer(Modifier.height(8.dp))
                InfoCard("提示", listOf(it))
            }
        }
        item {
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("导入聊天截图 · 后续开放") }
            Text("截图识别尚未接入；悬浮入口已可用。", style = MaterialTheme.typography.bodySmall)
        }
        item { InfoCard("内容只用于本次会话", listOf(
            "结束或返回首页后清除，不保留最近分析。",
            "切到后台时，会话可暂留内存供继续操作；请在完成后主动结束。",
            "应用进程重建后需要重新导入聊天。"
        )) }
    }
}
