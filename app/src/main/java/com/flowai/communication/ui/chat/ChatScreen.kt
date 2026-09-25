package com.flowai.communication.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flowai.communication.domain.ChatRole
import com.flowai.communication.domain.ChatTurn
import com.flowai.communication.ui.components.ChatBubble

/**
 * The follow-up conversation about the analysis on screen.
 *
 * Deliberately plain: bubbles for what has been said, one box for what to say next. The list
 * follows the newest turn so an answer never lands off-screen, and the send button sleeps while
 * a question is in flight — a second tap must not race the first reply.
 */
@Composable
fun ChatScreen(
    history: List<ChatTurn>,
    busy: Boolean,
    send: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    // Newest turn last: keep the exchange's tail in view, including the thinking placeholder.
    LaunchedEffect(history.size, busy) {
        if (history.isNotEmpty()) listState.animateScrollToItem(history.size)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp),
            state = listState,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("就这次分析追问", style = MaterialTheme.typography.titleLarge)
            }
            item {
                Text(
                    "模型会结合分析结论与原始内容回答；结束会话后这段对话一并清除。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(history) { turn ->
                ChatBubble(
                    label = if (turn.role == ChatRole.USER) "我" else "模型",
                    text = turn.text,
                    mine = turn.role == ChatRole.USER
                )
            }
            if (busy) {
                item { ChatBubble("模型", "正在思考…", false) }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 4,
                    placeholder = { Text("追问这次分析…") }
                )
                Button(
                    onClick = {
                        send(draft)
                        draft = ""
                    },
                    enabled = !busy && draft.isNotBlank()
                ) { Text("发送") }
            }
        }
    }
}
