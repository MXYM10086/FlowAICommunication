package com.flowai.communication.data.repository
import com.flowai.communication.data.model.*
import com.flowai.communication.domain.*

class ConversationRepository(
    private val parser: DialogueParser,
    private val builder: ConversationStateBuilder,
    private val nextActions: NextActionEngine,
    private val actionEngine: ChatToActionEngine,
    private val chatEngine: AnalysisChatEngine
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

    /**
     * Analyses a chat screenshot directly: the engine reads the conversation out of the image.
     *
     * There is deliberately no parsing step — no local text exists yet — so the text guards of
     * [analyze] do not apply and the capsule carries the image instead of messages. Only a
     * vision-capable remote engine can serve this; the caller decides when to take this path.
     */
    suspend fun analyzeScreenshot(imageBase64: String): AnalysisResult {
        require(imageBase64.isNotBlank()) { "截屏内容为空，请重新截取" }
        val context = ContextCapsule(
            SourceType.SCREENSHOT, messages = emptyList(), imageBase64 = imageBase64
        )
        val state = builder.build(context)
        return AnalysisResult(context, state, nextActions.recommend(state).sortedBy { it.priority }.take(3))
    }

    /**
     * One follow-up question about a finished analysis, answered in the context of that analysis.
     *
     * [history] holds the turns *preceding* [question] — engines append the question themselves,
     * so passing it inside the history too would send it twice.
     *
     * No guards here: the question is free prose addressed to the model, and the grounding (the
     * capsule and the state) already went through [analyze] or [analyzeScreenshot] once.
     */
    suspend fun chat(result: AnalysisResult, history: List<ChatTurn>, question: String): String =
        chatEngine.chat(result.capsule, result.state, history, question)

    suspend fun execute(result: AnalysisResult, action: NextAction): ActionResult {
        require(action in result.actions) { "动作不属于当前分析" }
        return actionEngine.execute(result.capsule, result.state, action)
    }
}
