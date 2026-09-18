package com.flowai.communication.data.repository
import com.flowai.communication.data.model.*
import com.flowai.communication.domain.*

class ConversationRepository(
    private val parser: DialogueParser,
    private val builder: ConversationStateBuilder,
    private val nextActions: NextActionEngine,
    private val actionEngine: ChatToActionEngine
) {
    // Stateless: callers own the current result; no history retains past contexts.
    //
    // Suspending because the engines may reach a network service. Parsing stays synchronous so an
    // empty or oversized input is rejected before any request is made — and so no text leaves the
    // device for input the user could have seen was invalid.
    suspend fun analyze(text: String, source: SourceType = SourceType.TEXT): AnalysisResult {
        require(text.length <= 20_000) { "聊天文本不能超过 20,000 字符" }
        val messages = parser.parse(text)
        require(messages.isNotEmpty()) { "请先输入有效的聊天内容" }
        val context = ContextCapsule(source, messages = messages, rawText = text)
        val state = builder.build(context)
        val result = AnalysisResult(context, state, nextActions.recommend(state).sortedBy { it.priority }.take(3))
        return result
    }

    suspend fun execute(result: AnalysisResult, action: NextAction): ActionResult {
        require(action in result.actions) { "动作不属于当前分析" }
        return actionEngine.execute(result.capsule, result.state, action)
    }
}
