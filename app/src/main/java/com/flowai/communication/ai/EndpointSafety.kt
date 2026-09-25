package com.flowai.communication.ai

import com.flowai.communication.BuildConfig
import java.net.URL

/**
 * 明文端点防护，远程分析引擎（[RemoteLlmService]）与回复卡片网络客户端
 * （`ai.deepseek.DeepSeekClient`）共用同一套判断。
 *
 * 会话文字只允许走 https；明文仅限"不可能离开本地网络"的地址，且仅限调试构建——
 * 调试版的 network security config 才放行明文连接，release 即使过了这层判断也连不上。
 */
internal fun URL.isSecureOrLocal(): Boolean {
    if (protocol == "https") return true
    if (protocol != "http") return false
    if (!BuildConfig.DEBUG) return false
    return isLoopbackOrPrivate()
}

/**
 * 回环地址与 RFC1918 私网段为真，另加模拟器的宿主机别名。
 *
 * 写成八位组比较而不是一张字面量表：这些网段是 IP 寻址本身的性质，
 * 不属于任何特定的人或机器。
 */
internal fun URL.isLoopbackOrPrivate(): Boolean {
    val h = host.orEmpty()
    if (h == "localhost" || h == "127.0.0.1" || h == "::1") return true
    // The host machine as seen from an Android emulator.
    if (h == "10.0.2.2") return true

    val octets = h.split('.')
    if (octets.size != 4) return false
    val first = octets[0].toIntOrNull() ?: return false
    val second = octets[1].toIntOrNull() ?: return false
    return when (first) {
        10 -> true                                   // 10.0.0.0/8
        172 -> second in 16..31                      // 172.16.0.0/12
        192 -> second == 168                         // 192.168.0.0/16
        else -> false
    }
}
