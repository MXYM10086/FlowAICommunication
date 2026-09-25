package com.flowai.communication.ui.card

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flowai.communication.util.ClipboardCopier

/**
 * 悬浮三风格回复卡片（桌宠一键流程的结果窗口）。
 *
 * 结构自上而下：
 * 1. **头部**——拖拽把手条 + 标题 +（阶段 2 起）「重新截屏」+「最小化」+「关闭」。
 *    整个头部是拖拽区：`detectDragGestures` 只消费超过触摸 slop 的移动，
 *    按钮的点击不受影响（点击先被按钮消费）。
 * 2. **可折叠「识别原文」**——默认收起；展开显示 OCR 文本（截断到
 *    [ReplyCardState.MAX_SOURCE_CHARS]，完整原文可达 2 万字，全渲染会撑爆卡片）。
 *    卡片整体可滚动，原文区不再套自己的滚动，避免嵌套滚动冲突。
 * 3. **三个风格区块**（温暖版 / 毒舌版 / 冷静科学版，顺序固定）——风格名 chip +
 *    正文；Done 时走打字机动画并显示【复制】按钮（Toast「已复制：温暖版」）；
 *    Error 时显示友好原因 +【重试这一条】（批量生成进行中隐藏，守串行约定）。
 * 4. **隐私脚注**——明示只有本机识别的文字会离开手机。
 *
 * 状态全部来自 [ReplyCardState]（活在组合外，View 重建不丢）；本组件无自有状态。
 *
 * @param maxHeight 卡片最大高度（由窗口宿主按屏高 62% 计算传入），超出部分卡片内滚动。
 * @param onRetryStyle 区块级重试回调，参数为区块下标（0=温暖 1=毒舌 2=冷静科学）。
 */
@Composable
fun ReplyCard(
    state: ReplyCardState,
    maxHeight: Dp,
    onDragBy: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onRetryStyle: (Int) -> Unit,
    onRecapture: (() -> Unit)? = null
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            Modifier
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
        ) {
            CardHeader(
                progress = state.progress,
                onDragBy = onDragBy,
                onDragEnd = onDragEnd,
                onMinimize = onMinimize,
                onClose = onClose,
                onRecapture = onRecapture
            )

            Spacer(Modifier.height(4.dp))
            SourceSection(state)

            state.cardError?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))
            state.replies.forEachIndexed { index, block ->
                StyleBlock(
                    block = block,
                    generating = state.progress != null,
                    onRetry = { onRetryStyle(index) },
                    onCopy = { text ->
                        // 复制统一走 ClipboardCopier；成功/失败都用 Toast 明确告知。
                        val ok = ClipboardCopier.copy(context, block.styleName, text)
                        Toast.makeText(
                            context,
                            if (ok) "已复制：${block.styleName}" else "复制失败，请长按文本手动复制",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
                Spacer(Modifier.height(8.dp))
            }

            PrivacyNote()
        }
    }
}

/** 头部：把手条 + 标题/进度 + 窗口按钮。整块是拖拽把手。 */
@Composable
private fun CardHeader(
    progress: String?,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onRecapture: (() -> Unit)?
) {
    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragBy(dragAmount.x, dragAmount.y)
                    }
                )
            }
    ) {
        // 把手提示条：视觉上告诉用户"这里可以拖"。
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(width = 40.dp, height = 4.dp),
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.outline
            ) {}
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("FlowAI 回复", style = MaterialTheme.typography.titleMedium)
                Text(
                    progress ?: "温暖 · 毒舌 · 冷静科学 三风格回复",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onRecapture != null) {
                TextButton(onClick = onRecapture) { Text("重新截屏") }
            }
            TextButton(onClick = onMinimize) { Text("最小化") }
            TextButton(onClick = onClose) { Text("关闭") }
        }
    }
}

/** 可折叠的「识别原文」区。 */
@Composable
private fun SourceSection(state: ReplyCardState) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { state.sourceCollapsed = !state.sourceCollapsed }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "识别原文",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (state.sourceCollapsed) "展开 ▾" else "收起 ▴",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
    if (!state.sourceCollapsed) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            if (state.ocrText.isBlank()) {
                Text(
                    "暂无识别文本。截屏识别后这里会显示聊天原文。",
                    Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val full = state.ocrText
                val shown = if (full.length > ReplyCardState.MAX_SOURCE_CHARS) {
                    full.take(ReplyCardState.MAX_SOURCE_CHARS) + "\n…（已截断，共 ${full.length} 字）"
                } else {
                    full
                }
                Text(
                    shown,
                    Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 单个风格区块：chip + 状态化正文 + 复制 / 重试按钮。 */
@Composable
private fun StyleBlock(
    block: StyleReplyUi,
    generating: Boolean,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        block.styleName,
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.weight(1f))
                val done = block.status as? ReplyBlockStatus.Done
                if (done != null) {
                    TextButton(onClick = { onCopy(done.text) }) { Text("复制") }
                }
            }
            when (val status = block.status) {
                ReplyBlockStatus.Idle -> Text(
                    "等待生成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ReplyBlockStatus.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Text("正在生成…", style = MaterialTheme.typography.bodySmall)
                }

                is ReplyBlockStatus.Done -> TypewriterText(status.text)

                is ReplyBlockStatus.Error -> Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        status.message,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    // 批量生成进行中隐藏重试：守住"串行"约定，不开并发的第二路。
                    if (!generating) {
                        TextButton(onClick = onRetry) { Text("重试这一条") }
                    }
                }
            }
        }
    }
}

/** 隐私脚注：与首页截屏分区同一口径的承诺。 */
@Composable
private fun PrivacyNote() {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            "只把本机识别出的文字发送生成回复，截屏原图绝不上传。",
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
