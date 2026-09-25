package com.flowai.communication.ai
import com.flowai.communication.data.model.*
import com.flowai.communication.data.repository.DemoConversations
import com.flowai.communication.domain.ChatTurn
import com.flowai.communication.domain.PlainTextDialogueParser

class MockLlmService : LlmService {
    private fun isDemo(context: ContextCapsule, demo: String): Boolean =
        context.messages.map { it.speaker to it.text } ==
            PlainTextDialogueParser().parse(demo).map { it.speaker to it.text }

    override suspend fun build(context: ContextCapsule): ConversationState {
        require(context.messages.isNotEmpty()) { "请先输入聊天内容" }
        return when {
            isDemo(context, DemoConversations.A) -> ConversationState(
                "任务完成进度", listOf("对方正在确认任务能否按时完成", "我方需要说明剩余工作和交付安排"),
                emptyList(), emptyList(), listOf("尚未明确具体完成时间", "任务紧急程度尚未确认"),
                listOf("最后回复较简短，可能存在保留意见或结束话题的倾向；无法据此判断情绪"),
                listOf("对方询问今天能否完成", "我方表示可能还差一点"), ConversationStage.FOLLOW_UP,
                "固定 Demo A 模拟结果；只描述可观察的沟通信息，不推断心理。")
            isDemo(context, DemoConversations.B) -> ConversationState(
                "组会安排与材料准备", listOf("老师安排组会并分配准备工作"),
                emptyList(), emptyList(), listOf("小王、小李是否确认接受任务", "相对日期对应的日历日期需确认"),
                listOf("消息包含明确的地点、时间与任务分工；尚未看到其他人确认"),
                listOf("明天下午三点在 A203 开会", "小王准备 PPT，小李整理数据", "材料今晚十点之前发给老师"),
                ConversationStage.DECISION, "固定 Demo B 模拟结果；任务分配不等于接收方已同意。")
            else -> ConversationState(
                "待确认的沟通话题", emptyList(), emptyList(), emptyList(),
                listOf("请确认讨论目标、负责人和时间要求"),
                listOf("已解析 ${context.messages.size} 条消息；当前 Mock 不分析非内置场景"),
                context.messages.take(3).map { it.text }, ConversationStage.UNKNOWN,
                "通用 Mock 模板，不是真实 AI 分析；不会套用 Demo 的人员、时间或任务。")
        }
    }
    override suspend fun recommend(state: ConversationState): List<NextAction> {
        val actions = when (state.topic) {
            "任务完成进度" -> listOf(
                action("deadline", "给出明确完成时间", "补充一个可兑现的交付时间", ActionType.GIVE_DEADLINE, "尚未明确具体完成时间", 1),
                action("progress", "汇报当前完成进度", "说明已完成和剩余的部分", ActionType.REPORT_PROGRESS, "“还差一点”缺少具体进度", 2),
                action("urgency", "确认任务是否紧急", "确认对方最晚需要的时间", ActionType.CLARIFY, "尚未确认任务紧急程度", 3))
            "组会安排与材料准备" -> listOf(
                action("tasks", "提取任务与事件", "整理组会及两项准备任务", ActionType.EXTRACT_TASK, "消息中有明确分工和会议安排", 1),
                action("event", "生成会议事件", "查看时间、地点和参与人员", ActionType.CREATE_EVENT, "消息中包含组会时间与地点", 2),
                action("summary", "确认安排", "用一段回复复述待确认事项", ActionType.SUMMARIZE, "尚未看到参与者确认", 3))
            else -> listOf(
                action("clarify", "明确沟通目标", "询问希望达成的结果", ActionType.CLARIFY, "当前目标尚未确定", 1),
                action("summarize", "核对关键信息", "请对方确认你的理解", ActionType.SUMMARIZE, "通用模板需要人工核对", 2),
                action("extract", "查看可提取事项", "检查是否有任务或事件", ActionType.EXTRACT_TASK, "当前 Mock 仅支持内置提取案例", 3))
        }
        return actions.sortedBy { it.priority }.take(3)
    }
    /**
     * Follow-up answers without a network: grounded in the analysis it is asked about, and
     * upfront about being a template. Configuring a model API is what turns this into a real
     * conversation.
     */
    override suspend fun chat(
        context: ContextCapsule,
        state: ConversationState,
        history: List<ChatTurn>,
        question: String
    ): String {
        val grounded = (state.keyFacts + state.unresolvedIssues + state.communicationSignals)
            .filter { it.isNotBlank() }.take(3)
        return buildString {
            append("（本机模拟回复）你问「").append(question).append("」。")
            append("围绕「").append(state.topic).append("」")
            if (grounded.isNotEmpty()) {
                append("，目前可依据的是：").append(grounded.joinToString("；")).append("。")
            } else {
                append("，当前分析还没有足够依据。")
            }
            append(" Mock 不联网、不理解追问语义；在「分析引擎设置」配置模型 API 后，这里会是真正的模型回答。")
        }
    }

    private fun action(id: String, title: String, description: String, type: ActionType, reason: String, priority: Int) =
        NextAction(id, title, description, type, reason, priority)

    override suspend fun execute(context: ContextCapsule, state: ConversationState, action: NextAction): ActionResult {
        require(action in recommend(state)) { "请从当前分析的推荐动作中选择" }
        if (action.type in listOf(ActionType.EXTRACT_TASK, ActionType.CREATE_EVENT)) {
            // Extraction is demo-only, and the Demo B button left the home page, so this branch is
            // what every real input now reaches. The message says why nothing came back instead of
            // sending the reader to a button that no longer exists.
            if (!isDemo(context, DemoConversations.B)) return ActionResult(note = "当前 Mock 只在示例文本上产出任务与事件。")
            val event = ActionObject.Event("组会", "明天 15:00", "A203", listOf("老师", "小王", "小李"))
            val objects = if (action.type == ActionType.CREATE_EVENT) listOf(event) else listOf(
                event, ActionObject.Task("准备 PPT", "小王", "今晚 22:00", "完成后发给老师"),
                ActionObject.Task("整理数据", "小李", "今晚 22:00", "完成后发给老师"))
            return ActionResult(objects = objects, note = "Mock 草稿，未写入系统日历或待办。参与人员来自消息，出席情况需确认；相对日期请核对。")
        }
        val texts = when {
            isDemo(context, DemoConversations.A) && action.type == ActionType.GIVE_DEADLINE -> listOf(
                "目前还差最后一部分，我今晚十点前整理好发你。", "还差最后一点，今晚十点前发你。",
                "目前剩余最后一部分内容，预计今晚十点前完成并发送。")
            isDemo(context, DemoConversations.A) && action.type == ActionType.REPORT_PROGRESS -> listOf(
                "我补充一下进度：已完成【内容】，还剩【内容】，确认时间后告诉你。", "已完成【内容】，剩余【内容】，完成时间待确认。",
                "当前已完成【内容】，剩余工作为【内容】，我会核实交付时间后同步。")
            isDemo(context, DemoConversations.A) -> listOf(
                "你最晚什么时候需要？我确认一下安排。", "最晚几点需要？", "请问这项任务最晚需要在什么时间交付？")
            isDemo(context, DemoConversations.B) -> listOf(
                "我确认一下：明天15点在A203开会，小王准备PPT，小李整理数据，材料今晚22点前发给您，对吗？",
                "确认：明天15点A203组会，两项材料今晚22点前交，对吗？",
                "请确认以上会议及任务安排：明日15:00于A203开会，小王准备PPT，小李整理数据，材料今晚22:00前提交。")
            else -> listOf("我们先确认一下，这次希望达成什么结果？", "想先确认目标和时间。", "请确认本次沟通的目标、分工及时间要求。")
        }
        return ActionResult(replies = listOf("自然", "简洁", "正式").zip(texts) { style, text -> ReplyCandidate(style, text) },
            note = "Mock 候选回复，请编辑核实后复制。“今晚十点”和【占位内容】是示例，不是已知承诺；不会自动发送。")
    }
}
