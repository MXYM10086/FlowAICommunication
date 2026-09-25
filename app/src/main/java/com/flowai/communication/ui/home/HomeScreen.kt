package com.flowai.communication.ui.home
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.flowai.communication.ai.EngineSettingsStore
import com.flowai.communication.data.repository.DemoConversations
import com.flowai.communication.system.CaptureAccessibilityService
import com.flowai.communication.system.FloatingAssistantService
import com.flowai.communication.system.OverlayPermission
import com.flowai.communication.system.pet.PetSkins
import com.flowai.communication.system.pet.PrefsPetSkinStore
import com.flowai.communication.ui.components.*

@Composable fun HomeScreen(
    open: (String?) -> Unit,
    clearedNotice: String?,
    captureNotice: String? = null,
    onRequestCapture: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSkins: () -> Unit = {}
) {
    LazyColumn(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("让沟通有下一步", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("把一段聊天粘进来，先看懂沟通状态，再决定怎么回、怎么做。", style = MaterialTheme.typography.bodyLarge)
        }
        item { EngineNote() }
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
                "悬浮桌宠",
                listOf(
                    if (!granted) "需要先在系统设置里允许「显示在其他应用上层」。"
                    else if (running) "桌宠已开启，可在其他应用上方随时点开 FlowAI。"
                    else "已获得权限，可以开启桌宠。",
                    "桌宠只作为入口，不会自动读取任何聊天内容。",
                    // Reinstalling the app clears this permission on some ROMs, which looks like the
                    // pet silently vanished; say so instead of leaving the user guessing.
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
                ) { Text(if (running) "关闭桌宠" else "开启桌宠") }
                Spacer(Modifier.height(6.dp))
                // Same panel the pet opens, reachable without the overlay.
                OutlinedButton(
                    onClick = { FloatingAssistantService.openPanel(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("打开助手面板") }
                Spacer(Modifier.height(6.dp))
                // 三风格回复卡片测试入口：overlay 窗口无法由 adb 注入触摸，桌宠单击只能人工
                // 触发，验收（拖拽/吸边/最小化/复制）需要一个能点的入口。阶段 1 弹样例文本。
                OutlinedButton(
                    onClick = { FloatingAssistantService.showTestCard(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("测试回复卡片（样例文本）") }
                Spacer(Modifier.height(6.dp))
                // 桌宠一键的等价入口（overlay 触摸无法 adb 注入）：截屏 → 本机 OCR → 卡片。
                OutlinedButton(
                    onClick = { FloatingAssistantService.startCardCapture(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("截屏识别 → 回复卡片") }
            }
        }
        item {
            val context = LocalContext.current
            val store = remember { PrefsPetSkinStore(context.applicationContext) }
            // Re-read on resume so the row reflects a skin chosen on the picker screen.
            var skin by remember { mutableStateOf(PetSkins.byId(store.load())) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) skin = PetSkins.byId(store.load())
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            InfoCard(
                "桌宠皮肤",
                listOf(
                    "桌宠会自己眨眼、蹦跳、摇摆、转圈、打盹；点它有粒子特效，长按可以直接换下一款。",
                    "当前皮肤：${skin.name}"
                )
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenSkins, modifier = Modifier.fillMaxWidth()) {
                Text("选择桌宠皮肤")
            }
        }
        item {
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            // Re-read on resume: the service is enabled in system settings and reports back here.
            var silent by remember { mutableStateOf(CaptureAccessibilityService.isRunning) }
            // Recognition always happens on device; the engine only decides where the recognised
            // text is analysed — remotely when configured and agreed to, locally otherwise.
            var remote by remember {
                mutableStateOf(EngineSettingsStore(context).load().canUseRemote)
            }
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        silent = CaptureAccessibilityService.isRunning
                        remote = EngineSettingsStore(context).load().canUseRemote
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            InfoCard("截屏分析（测试版）", listOf(
                "点桌宠一键截屏，或在当前界面框选聊天区域后确认。",
                "框选确认后先在本机识别截屏中的聊天文字，再把文字交给模型分析；截屏图片不离开手机。",
                if (remote) "远程模型已配置：识别出的文字上传分析（你已同意上传）。"
                else "尚未配置远程模型：识别出的文字在本机分析。",
                "桌宠一键截屏的分析与回复直接出现在悬浮面板，不离开聊天应用；下面的按钮则在主界面出结果。",
                "只截取你框选的区域；截屏帧用完立即释放，不会持续录屏。",
                "开启桌宠时会先请求一次屏幕共享；共享开启后框选确认立即截屏，无授权弹窗。",
                if (silent) "已开启静音截屏：框选确认后直接出结果，无授权弹窗。"
                else "未开启屏幕共享或静音截屏时，系统每次截屏都会弹一次授权框。"
            ))
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRequestCapture,
                modifier = Modifier.fillMaxWidth()
            ) { Text("截屏分析聊天内容") }
            if (!silent) {
                Spacer(Modifier.height(6.dp))
                // One-time system setup; afterwards captures need no consent dialog at all.
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("开启静音截屏（免授权弹窗）") }
            }
            Text(
                "分析结果直接进入同一个会话流程。",
                style = MaterialTheme.typography.bodySmall
            )
            captureNotice?.let {
                Spacer(Modifier.height(8.dp))
                InfoCard("提示", listOf(it))
            }
        }
        item {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("分析引擎设置")
            }
            Text(
                "默认在本机分析，聊天内容不离开手机。也可以填写自己的分析服务地址，让内容上传分析（需另行同意）。",
                style = MaterialTheme.typography.bodySmall
            )
        }
        item { InfoCard("内容只用于本次会话", listOf(
            "结束或返回首页后清除，不保留最近分析。",
            "切到后台时，会话可暂留内存供继续操作；请在完成后主动结束。",
            "应用进程重建后需要重新导入聊天。"
        )) }
    }
}
