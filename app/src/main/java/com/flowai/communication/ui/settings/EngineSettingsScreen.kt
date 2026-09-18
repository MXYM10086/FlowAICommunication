package com.flowai.communication.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.flowai.communication.ai.EngineSettings
import com.flowai.communication.ai.EngineSettingsStore
import com.flowai.communication.ui.components.InfoCard

/**
 * Where the user decides whether this app analyses on the phone or sends text to a service.
 *
 * The app ships no provider credential — the endpoint fronting the model holds it. Consent and
 * configuration are separate here on purpose: filling in a URL does not start uploading anything,
 * and the wording says exactly what leaves the device before anything can.
 */
@Composable
fun EngineSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { EngineSettingsStore(context) }
    var saved by remember { mutableStateOf(store.load()) }
    var endpoint by remember { mutableStateOf(saved.endpoint) }
    var token by remember { mutableStateOf(saved.accessToken) }
    var status by remember { mutableStateOf<String?>(null) }

    // Consent is asked for separately, only when a destination is actually set.
    var askingConsent by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("分析引擎", style = MaterialTheme.typography.headlineSmall)

        val mode = when {
            !saved.isConfigured -> "本机分析（不上传）"
            !saved.hasConsent -> "已填写端点，但尚未同意上传 —— 仍在本机分析"
            else -> "上传到已配置的服务分析"
        }
        InfoCard(
            "当前模式",
            listOf(
                mode,
                if (saved.isConfigured) "端点：${saved.endpoint}" else "未配置端点，聊天内容不会离开手机。"
            )
        )

        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it.trim(); status = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("分析服务地址（https://…）") },
            supportingText = { Text("留空即在本机分析。地址由你或运营方部署，密钥保存在该服务上，不进 App。") }
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; status = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("访问令牌（可选）") },
            supportingText = { Text("仅当你的服务要求鉴权时填写。") }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    store.save(EngineSettings(endpoint = endpoint, accessToken = token, consentedAt = saved.consentedAt))
                    saved = store.load()
                    // Changing the destination drops the previous agreement.
                    status = if (saved.isConfigured && !saved.hasConsent) {
                        "已保存。要让内容上传分析，还需要下面的「同意并启用」。"
                    } else {
                        "已保存。"
                    }
                }
            ) { Text("保存") }

            if (saved.isConfigured && saved.hasConsent) {
                OutlinedButton(
                    onClick = {
                        store.save(saved.copy(consentedAt = 0L))
                        saved = store.load()
                        status = "已停止上传，改回本机分析。"
                    }
                ) { Text("停止上传") }
            } else if (saved.isConfigured) {
                Button(
                    onClick = { askingConsent = true },
                    enabled = saved.endpoint.startsWith("https://")
                ) { Text("同意并启用") }
            }
        }

        if (saved.isConfigured && !saved.endpoint.startsWith("https://")) {
            Text(
                "端点必须是 https:// —— 聊天内容是私密信息，明文发送会被拒绝。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        InfoCard(
            "关于隐私",
            listOf(
                "本机分析：内容不离开手机，功能与上传时相同（内置演示结果是固定的）。",
                "上传分析：只有你主动粘贴、分享、划词或截屏得到的文字会被发送。",
                "不会读取聊天数据库，不会持续监听，不保留历史。",
                "API 密钥不放在 App 里 —— 配置在你自己部署的服务上。"
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
                        "${saved.endpoint}\n\n" +
                        "用于生成分析与回复建议。不发送时不会上传任何内容；可以随时在这里停止。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.recordConsent(System.currentTimeMillis())
                    saved = store.load()
                    askingConsent = false
                    status = "已启用上传分析。"
                }) { Text("同意并启用") }
            },
            dismissButton = {
                TextButton(onClick = { askingConsent = false }) { Text("取消") }
            }
        )
    }
}
