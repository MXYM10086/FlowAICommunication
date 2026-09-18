package com.flowai.communication.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.flowai.communication.ai.EngineMode
import com.flowai.communication.ai.EngineSettingsStore
import com.flowai.communication.ui.components.InfoCard

/**
 * Turns the model API on or off.
 *
 * Two states only: analyse on the phone, or call the model API with a key. The key is entered here
 * rather than compiled in, and nothing is uploaded until the user agrees.
 */
@Composable
fun EngineSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { EngineSettingsStore(context) }
    var saved by remember { mutableStateOf(store.load()) }
    var draft by remember { mutableStateOf(saved) }
    var status by remember { mutableStateOf<String?>(null) }
    var askingConsent by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("模型 API", style = MaterialTheme.typography.headlineSmall)

        InfoCard(
            "当前状态",
            listOf(
                when {
                    saved.mode == EngineMode.LOCAL -> "本机分析 · 内容不离开手机"
                    !saved.isConfigured -> "已选调用 API，但还没填 Key · 当前仍在本机分析"
                    !saved.hasConsent -> "Key 已填，尚未同意上传 · 当前仍在本机分析"
                    else -> "调用 API 分析 · 你主动提供的内容会发送给模型服务"
                }
            )
        )

        OutlinedTextField(
            value = draft.apiKey,
            onValueChange = { draft = draft.copy(apiKey = it.trim()); status = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            // A credential should not sit in plain view on screen.
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("模型 API Key") },
            supportingText = { Text("留空即在本机分析。") }
        )
        OutlinedTextField(
            value = draft.providerUrl,
            onValueChange = { draft = draft.copy(providerUrl = it.trim()); status = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("接口地址") },
            supportingText = { Text("默认 DeepSeek。兼容 OpenAI /chat/completions 的服务都可直接用。") }
        )
        OutlinedTextField(
            value = draft.model,
            onValueChange = { draft = draft.copy(model = it.trim()); status = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("模型名称") }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val updated = draft.copy(
                    mode = if (draft.apiKey.isBlank()) EngineMode.LOCAL else EngineMode.REMOTE
                )
                store.save(updated)
                saved = store.load(); draft = saved
                status = when {
                    saved.mode == EngineMode.LOCAL -> "已保存，继续在本机分析。"
                    !saved.hasConsent -> "已保存。要让内容上传分析，还需要点「同意并启用」。"
                    else -> "已保存。"
                }
            }) { Text("保存") }

            if (saved.canUseRemote) {
                OutlinedButton(onClick = {
                    store.save(saved.copy(consentedAt = 0L))
                    saved = store.load(); draft = saved
                    status = "已停止上传，改回本机分析。"
                }) { Text("停止上传") }
            } else if (saved.mode == EngineMode.REMOTE && saved.isConfigured) {
                Button(onClick = { askingConsent = true }) { Text("同意并启用") }
            }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        InfoCard(
            "关于隐私",
            listOf(
                "本机分析：内容不离开手机，离线可用。",
                "调用 API：只发送你主动粘贴、分享、划词或截屏得到的文字；不读取聊天数据库、不持续监听、不保留历史。",
                "Key 只存在这台手机的应用私有存储里。分享这个 APK 给别人前请先清空 Key —— 它可以被解包读取。"
            )
        )

        TextButton(onClick = onBack) { Text("返回") }
    }

    if (askingConsent) {
        AlertDialog(
            onDismissRequest = { askingConsent = false },
            title = { Text("确认开始上传？") },
            text = {
                Text(
                    "接下来，你主动提供（粘贴、分享、划词、截屏）的聊天文字会被发送到：\n\n" +
                        "${saved.providerUrl}\n\n" +
                        "用于生成分析与回复建议。不使用时不会上传任何内容；可以随时在这里停止。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.recordConsent(System.currentTimeMillis())
                    saved = store.load(); draft = saved
                    askingConsent = false
                    status = "已启用 API 分析。"
                }) { Text("同意并启用") }
            },
            dismissButton = { TextButton(onClick = { askingConsent = false }) { Text("取消") } }
        )
    }
}
