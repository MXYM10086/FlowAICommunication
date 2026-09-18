package com.flowai.communication.ui.analysis
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flowai.communication.data.model.*
import com.flowai.communication.ui.components.*

@Composable fun AnalysisScreen(result: AnalysisResult, choose: (NextAction) -> Unit) {
    val s = result.state
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(s.topic, style = MaterialTheme.typography.headlineMedium) }
        item { MockNote(s.confidenceNote ?: "Mock 模拟分析") }
        item { InfoCard("当前沟通状态 · ${stageLabel(s.stage)}", s.participantGoals) }
        item { InfoCard("沟通信号", s.communicationSignals) }
        item { InfoCard("未解决问题", s.unresolvedIssues) }
        item { Text("推荐下一步", style = MaterialTheme.typography.titleLarge) }
        result.actions.forEachIndexed { index, action -> item {
            Card(onClick = { choose(action) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${index + 1}  ${action.title}", style = MaterialTheme.typography.titleMedium)
                    Text(action.description)
                    Text("推荐理由：${action.reason}", style = MaterialTheme.typography.bodySmall)
                    Text("查看结果 →", color = MaterialTheme.colorScheme.primary)
                }
            }
        } }
        item { InfoCard("已知事实", s.keyFacts) }
        item { InfoCard("已达成共识", s.agreements, "尚未发现双方明确确认的共识") }
        item { InfoCard("明确分歧", s.disagreements, "尚未发现明确表达的分歧") }
        item { InfoCard("解析后的消息 · ${result.capsule.messages.size}", result.capsule.messages.map { "${it.order + 1}. ${speakerLabel(it.speaker)}：${it.text}" }) }
    }
}
private fun speakerLabel(s: Speaker) = when (s) { Speaker.ME -> "我"; Speaker.OTHER -> "对方"; Speaker.OTHER_2 -> "对方 2"; Speaker.UNKNOWN -> "未知说话人" }
private fun stageLabel(s: ConversationStage) = when(s) {
    ConversationStage.OPENING -> "开场"; ConversationStage.DISCUSSION -> "讨论"
    ConversationStage.NEGOTIATION -> "协商"; ConversationStage.DECISION -> "安排 / 决策"
    ConversationStage.FOLLOW_UP -> "跟进"; ConversationStage.CLOSING -> "收尾"; ConversationStage.UNKNOWN -> "待确认"
}
