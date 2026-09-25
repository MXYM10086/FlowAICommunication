package com.flowai.communication.ui.card

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 打字机节奏：每步之间的延迟（毫秒）。约 50 字/秒，接近人的阅读速度。 */
internal const val TYPEWRITER_STEP_DELAY_MS = 20L

/** 总步数上限：再长的文本也在约 2.5 秒内打完，不无休止地拖。 */
internal const val TYPEWRITER_MAX_STEPS = 125

/**
 * 每步前进的字符数（纯函数，可 JVM 单测）。
 *
 * 短文本一步 1 个字（最自然）；长文本按比例放大步长，把总步数压在
 * [TYPEWRITER_MAX_STEPS] 以内——动画时长可预期，不随文本线性膨胀。
 * 返回值恒 ≥ 1，调用方无需再防 0 步死循环。
 */
internal fun typewriterStep(textLength: Int): Int {
    if (textLength <= 0) return 1
    return maxOf(1, (textLength + TYPEWRITER_MAX_STEPS - 1) / TYPEWRITER_MAX_STEPS)
}

/**
 * 把 [fullText] 以打字机动画逐字展示。
 *
 * 整段返回 + 客户端动画（不是 SSE 流式）：串行请求每一路拿到完整回复后由这里"打出来"。
 * [fullText] 变化时从头重新播放；卡片关闭 / 组合销毁时 [LaunchedEffect] 随协程取消，
 * 不留游离协程。每帧只改一个 Int 状态，纯 Compose 主线程操作，不阻塞、不丢帧。
 */
@Composable
fun TypewriterText(
    fullText: String,
    modifier: Modifier = Modifier
) {
    var visibleChars by remember(fullText) { mutableIntStateOf(0) }
    LaunchedEffect(fullText) {
        val step = typewriterStep(fullText.length)
        var shown = 0
        while (shown < fullText.length && isActive) {
            shown = minOf(shown + step, fullText.length)
            visibleChars = shown
            delay(TYPEWRITER_STEP_DELAY_MS)
        }
    }
    Text(
        text = fullText.take(visibleChars),
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium
    )
}
