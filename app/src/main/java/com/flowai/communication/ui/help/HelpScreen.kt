package com.flowai.communication.ui.help

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.flowai.communication.ai.EngineSettingsStore
import com.flowai.communication.system.CaptureAccessibilityService
import com.flowai.communication.system.FloatingAssistantService
import com.flowai.communication.system.OverlayPermission
import com.flowai.communication.system.pet.PrefsPetSkinStore
import com.flowai.communication.system.pet.PetSkins
import com.flowai.communication.ui.components.InfoCard

/**
 * Everything the home page used to stack below its entry buttons.
 *
 * The home page is now a single non-scrolling screen holding only the two routes, so the
 * explanatory blocks it carried — how the routes differ, what the pet and the capture chain
 * actually do to your data, what happens to a session — moved here. So did the entry points that
 * are occasional rather than one of the two main routes (the assistant panel, the reply card, the
 * silent-capture setup), so that no existing capability became unreachable in the reorganisation.
 *
 * This page *is* scrollable: it is reference text, read on demand, not a screen of actions.
 */
@Composable
fun HelpScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val skinStore = remember { PrefsPetSkinStore(context.applicationContext) }

    // All four are re-read on resume: the permission and the service are granted in system
    // settings, and the skin can be changed on its own screen.
    var granted by remember { mutableStateOf(OverlayPermission.isGranted(context)) }
    var running by remember { mutableStateOf(FloatingAssistantService.isRunning) }
    var silent by remember { mutableStateOf(CaptureAccessibilityService.isRunning) }
    var remote by remember { mutableStateOf(EngineSettingsStore(context).load().canUseRemote) }
    var skin by remember { mutableStateOf(PetSkins.byId(skinStore.load())) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = OverlayPermission.isGranted(context)
                running = FloatingAssistantService.isRunning
                silent = CaptureAccessibilityService.isRunning
                remote = EngineSettingsStore(context).load().canUseRemote
                skin = PetSkins.byId(skinStore.load())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        // No width cap here on purpose: the sibling reference screens (engine settings, skin
        // picker) are plain full-width scrollers, and a capped column outside a centring Box would
        // just sit against the left edge on a tablet.
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("使用说明", style = MaterialTheme.typography.headlineSmall)
        Text(
            "首页只放两条路，其余都在这一页。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        InfoCard(
            "两条路怎么选",
            listOf(
                "路径 A · 复制文本：把聊天粘贴或分享进来，看到沟通状态后选一个建议。",
                "路径 B · 桌宠截图：桌宠浮在聊天应用上方，框选屏幕内容，结果不用离开当前对话。",
                "两条路互不依赖，也不用事先开启什么。"
            )
        )

        Text("路径 A · 复制文本", style = MaterialTheme.typography.titleMedium)
        InfoCard(
            "怎么用",
            listOf(
                "每行一条消息，支持「说话人：内容」的中文或英文冒号。",
                "示例会一键载入一段写好的聊天，照着点一遍就懂了。",
                "在微信等应用选中文字 → 分享 → FlowAI，也会直接带进来。",
                "没有实现登录、数据库、语音、长期记忆或自动发消息。"
            )
        )

        Text("路径 B · 桌宠与截屏", style = MaterialTheme.typography.titleMedium)
        InfoCard(
            "悬浮桌宠",
            listOf(
                when {
                    !granted -> "需要先在系统设置里允许「显示在其他应用上层」。"
                    running -> "桌宠已开启，可在其他应用上方随时点开 FlowAI。"
                    else -> "已获得权限，可以开启桌宠。"
                },
                "桌宠只作为入口，不会自动读取任何聊天内容。",
                // Reinstalling clears this permission on some ROMs, which looks like the pet
                // silently vanished; say so instead of leaving the user guessing.
                if (!granted) "提示：重新安装应用后该权限可能会被系统清除，需要重新授权。" else ""
            ).filter { it.isNotEmpty() }
        )
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
        }

        InfoCard(
            "截屏识别（测试版）",
            listOf(
                "点桌宠一键截屏，或在当前界面框选聊天区域后确认。",
                "框选确认后先在本机识别截屏中的聊天文字，再把文字交给模型分析；截屏图片不离开手机。",
                if (remote) "远程模型已配置：识别出的文字上传分析（你已同意上传）。"
                else "尚未配置远程模型：识别出的文字在本机分析。",
                "桌宠一键截屏的分析与回复直接出现在悬浮面板，不离开聊天应用；首页的按钮则在主界面出结果。",
                "只截取你框选的区域；截屏帧用完立即释放，不会持续录屏。",
                "开启桌宠时会先请求一次屏幕共享；共享开启后框选确认立即截屏，无授权弹窗。",
                if (silent) "已开启静音截屏：框选确认后直接出结果，无授权弹窗。"
                else "未开启屏幕共享或静音截屏时，系统每次截屏都会弹一次授权框。"
            )
        )
        Button(onClick = { FloatingAssistantService.startCardCapture(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("截屏识别 → 回复卡片")
        }
        if (!silent) {
            Spacer(Modifier.height(2.dp))
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

        Text("桌宠皮肤", style = MaterialTheme.typography.titleMedium)
        InfoCard(
            "关于桌宠",
            listOf(
                "桌宠会自己眨眼、蹦跳、摇摆、转圈、打盹；点它有粒子特效，长按可以直接换下一款。",
                "当前皮肤：${skin.name}",
                "皮肤是程序化绘制的调色板，不是图片资源，因此不增加安装包体积。"
            )
        )

        InfoCard(
            "内容只用于本次会话",
            listOf(
                "结束或返回首页后清除，不保留最近分析。",
                "切到后台时，会话可暂留内存供继续操作；请在完成后主动结束。",
                "应用进程重建后需要重新导入聊天。",
                "分享进来的原文只以哈希形式记 15 分钟用于去重，不存原文。"
            )
        )

        Text("其他入口", style = MaterialTheme.typography.titleMedium)
        InfoCard(
            "备用打开方式",
            listOf(
                "助手面板：不经过桌宠也能用的同一套输入与分析面板。",
                "测试回复卡片：用一段样例文本直接打开三风格回复卡片，不经过截屏。"
            )
        )
        OutlinedButton(
            onClick = { FloatingAssistantService.openPanel(context) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("打开助手面板") }
        Spacer(Modifier.height(2.dp))
        OutlinedButton(
            onClick = { FloatingAssistantService.showTestCard(context) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("测试回复卡片（样例文本）") }
    }
}
