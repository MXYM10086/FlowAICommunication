package com.flowai.communication.ui.panel

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.flowai.communication.data.model.ActionObject
import com.flowai.communication.data.model.AnalysisResult
import com.flowai.communication.data.model.ConversationStage
import com.flowai.communication.data.model.NextAction
import com.flowai.communication.data.model.ReplyCandidate
import com.flowai.communication.domain.ChatRole
import com.flowai.communication.domain.ChatTurn
import com.flowai.communication.ui.components.ChatBubble

/**
 * What the engine produced for one chosen action.
 *
 * Carries the action's id so the panel can tell a stale result from the current selection.
 */
data class ExecutedAction(
    val actionId: String,
    val replies: List<ReplyCandidate>,
    val objects: List<ActionObject>,
    val note: String
)

/**
 * State the panel must not lose when its view is rebuilt.
 *
 * The panel's window is torn down while a capture runs, and re-attaching the same detached view
 * left the rebuilt hierarchy unable to receive touches. Recreating the view fixes that, but
 * anything held in `remember` would then be lost, so it lives here and outlives the view.
 */
class AssistantPanelState(initialText: String? = null) {
    var input by mutableStateOf(initialText.orEmpty())
    var result by mutableStateOf<AnalysisResult?>(null)
    var chosen by mutableStateOf<NextAction?>(null)
    var executed by mutableStateOf<ExecutedAction?>(null)
    var error by mutableStateOf<String?>(null)
    var copied by mutableStateOf<String?>(null)
    /** True while a captured frame is being read; the panel shows progress instead of buttons. */
    var analyzing by mutableStateOf(false)
    /** Follow-up turns about the analysis on screen; cleared with the analysis. */
    var chat by mutableStateOf<List<ChatTurn>>(emptyList())
    var chatBusy by mutableStateOf(false)
}

/**
 * The in-place assistant panel.
 *
 * Shaped for use *while the user is still in the chat app*: a bottom sheet that takes pasted text,
 * shows what the conversation is doing, and offers the next actions plus candidate replies,
 * without navigating away.
 *
 * It states plainly that only the user-chosen region is read. Captures happen on demand through
 * the panel's own button (or a pet tap), and the frame is released right after analysis, which
 * keeps the panel on the right side of both the platform's screen-share protection and the
 * product's Just-in-Time Context rule.
 */
@Composable
fun AssistantPanel(
    state: AssistantPanelState,
    analyze: suspend (String) -> AnalysisResult?,
    execute: suspend (AnalysisResult, NextAction) -> ExecutedAction?,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    /**
     * Starts a screen capture. The panel is hidden by the caller first, so the overlay itself is
     * not part of the captured frame; the text arrives later through [capturedText].
     */
    onCapture: (() -> Unit)? = null,
    /** Text produced by a capture, delivered after the capture activity returns. */
    capturedText: String? = null,
    /** Failure reported by a capture, if any. */
    captureFailure: String? = null,
    /** Asks the engine a follow-up question about the analysis on screen. */
    onChat: (String) -> Unit = {},
    /**
     * Upper bound for the panel's height.
     *
     * Passed in rather than hard-coded so the panel can be sized against the space actually
     * available — which shrinks while the keyboard is up. A fixed cap left the lower buttons
     * off-screen on tall phones with the keyboard showing.
     */
    maxHeight: Dp
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var input by state::input
    var result by state::result
    var chosen by state::chosen
    var executed by state::executed
    var error by state::error
    var copied by state::copied
    var analyzing by state::analyzing
    var chat by state::chat
    var chatBusy by state::chatBusy
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Engine calls may reach a network service, so the buttons need a progress state; otherwise a
    // slow response looks like a dead button.
    var busy by remember { mutableStateOf(false) }
    var chatDraft by remember { mutableStateOf("") }

    // A capture fills the input box, replacing whatever was there.
    LaunchedEffect(capturedText) {
        if (!capturedText.isNullOrBlank()) {
            input = capturedText.take(20_000)
            result = null; chosen = null; executed = null; copied = null; error = null
            chat = emptyList()
        }
    }
    LaunchedEffect(captureFailure) {
        if (!captureFailure.isNullOrBlank()) error = captureFailure
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .heightIn(max = maxHeight)
                .verticalScroll(scroll)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FlowAI 助手", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("收起") }
            }
            PanelNote("截屏只读取你框选的区域，用完立即释放；也可以直接粘贴聊天文字。")
            if (analyzing) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("正在识别截屏文字并分析，稍候…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            val analyzed = result
            if (analyzed == null) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(20_000); error = null },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    label = { Text("粘贴聊天内容（每行一条消息）") }
                )
                Spacer(Modifier.height(8.dp))
                onCapture?.let { startCapture ->
                    Button(
                        onClick = startCapture,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("截屏分析（框选聊天区域）") }
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val t = clipboard.getText()?.text.orEmpty()
                        if (t.isNotBlank()) {
                            input = t.take(20_000); error = null
                        } else {
                            error = "剪贴板里没有文字"
                            Toast.makeText(
                                context,
                                "剪贴板里没有文字，请先复制一段聊天",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) { Text("粘贴剪贴板") }
                    Button(
                        // Enabled only with something to analyse, and not while a call is running.
                        // A button that is always tappable but silently does nothing reads as "the
                        // app is broken"; a disabled one explains itself.
                        enabled = input.isNotBlank() && !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val r = analyze(input)
                                    if (r == null) {
                                        error = "分析失败，请检查内容"
                                        Toast.makeText(context, "分析失败，请检查内容", Toast.LENGTH_SHORT).show()
                                    } else {
                                        error = null
                                        result = r
                                    }
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (busy) "分析中…" else if (input.isBlank()) "先粘贴或截屏" else "分析") }
                }
                if (input.isBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "输入框为空：请粘贴聊天内容，或点上面的「截屏分析」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(Modifier.height(6.dp))
                ConversationSummary(analyzed)
                Spacer(Modifier.height(12.dp))
                Text("推荐下一步", style = MaterialTheme.typography.titleMedium)
                analyzed.actions.forEachIndexed { i, a ->
                    Card(
                        onClick = {
                            if (busy) return@Card
                            chosen = a
                            executed = null
                            copied = null
                            busy = true
                            scope.launch {
                                try {
                                    executed = execute(analyzed, a)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (chosen?.id == a.id) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${i + 1}  ${a.title}", style = MaterialTheme.typography.titleSmall)
                            Text(a.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                val current = chosen
                val done = executed?.takeIf { current != null && it.actionId == current.id }
                if (current != null && done != null) {
                    Spacer(Modifier.height(12.dp))
                    if (done.replies.isNotEmpty()) {
                        Text("候选回复 · 可复制后自行发送", style = MaterialTheme.typography.titleMedium)
                        PanelNote(done.note)
                        done.replies.forEach { reply ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(reply.style, style = MaterialTheme.typography.titleSmall)
                                    Text(reply.text, style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = { onCopy(clipboard, reply.style, reply.text) { copied = it } }) {
                                        Text("复制这条")
                                    }
                                }
                            }
                        }
                    }
                    if (done.objects.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("提取到的事项", style = MaterialTheme.typography.titleMedium)
                        done.objects.forEach { Text("· ${describe(it)}", style = MaterialTheme.typography.bodyMedium) }
                        TextButton(onClick = {
                            onCopy(clipboard, "事项清单", done.objects.joinToString("\n") { describe(it) }) { copied = it }
                        }) { Text("复制事项清单") }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("就这次分析追问", style = MaterialTheme.typography.titleMedium)
                chat.forEach { turn ->
                    Spacer(Modifier.height(6.dp))
                    ChatBubble(
                        label = if (turn.role == ChatRole.USER) "我" else "模型",
                        text = turn.text,
                        mine = turn.role == ChatRole.USER
                    )
                }
                if (chatBusy) {
                    Spacer(Modifier.height(6.dp))
                    ChatBubble("模型", "正在思考…", false)
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = chatDraft,
                        onValueChange = { chatDraft = it },
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 3,
                        placeholder = { Text("追问这次分析…") }
                    )
                    Button(
                        onClick = {
                            onChat(chatDraft)
                            chatDraft = ""
                        },
                        enabled = chatDraft.isNotBlank() && !chatBusy
                    ) { Text("发送") }
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        result = null; chosen = null; executed = null; copied = null
                        chat = emptyList()
                    }) { Text("换一段") }
                    OutlinedButton(onClick = {
                        onCopy(clipboard, "状态与建议", summarize(analyzed)) { copied = it }
                    }) { Text("复制结果") }
                }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            copied?.let {
                Spacer(Modifier.height(6.dp))
                Text("已复制：$it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) { Text("在完整界面中打开") }
        }
    }
}

@Composable
private fun ConversationSummary(result: AnalysisResult) {
    val s = result.state
    Text(s.topic, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
    Text("当前沟通状态 · ${stageLabel(s.stage)}", style = MaterialTheme.typography.titleSmall)
    s.participantGoals.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
    if (s.unresolvedIssues.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("未解决", style = MaterialTheme.typography.titleSmall)
        s.unresolvedIssues.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
    }
    if (s.communicationSignals.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("沟通信号", style = MaterialTheme.typography.titleSmall)
        s.communicationSignals.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
    }
}

private fun onCopy(
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    label: String,
    text: String,
    onDone: (String) -> Unit
) {
    clipboard.setText(AnnotatedString(text))
    onDone(label)
}

private fun summarize(result: AnalysisResult): String = buildString {
    appendLine(result.state.topic)
    result.state.participantGoals.forEach { appendLine("· $it") }
    result.state.unresolvedIssues.forEach { appendLine("未解决：$it") }
    result.actions.forEachIndexed { i, a -> appendLine("${i + 1}. ${a.title}") }
}.trim()

private fun describe(obj: ActionObject): String = when (obj) {
    is ActionObject.Task -> "任务：${obj.title}｜负责人：${obj.assignee ?: "待确认"}｜截止：${obj.deadline ?: "待确认"}"
    is ActionObject.Event -> "事件：${obj.title}｜${obj.time ?: "待确认"}｜${obj.location ?: "待确认"}"
    is ActionObject.Decision -> "决定：${obj.content}"
}

@Composable
private fun PanelNote(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun stageLabel(s: ConversationStage) = when (s) {
    ConversationStage.OPENING -> "开场"
    ConversationStage.DISCUSSION -> "讨论"
    ConversationStage.NEGOTIATION -> "协商"
    ConversationStage.DECISION -> "安排 / 决策"
    ConversationStage.FOLLOW_UP -> "跟进"
    ConversationStage.CLOSING -> "收尾"
    ConversationStage.UNKNOWN -> "待确认"
}
