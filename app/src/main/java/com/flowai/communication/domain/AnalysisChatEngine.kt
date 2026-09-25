package com.flowai.communication.domain
import com.flowai.communication.data.model.*

/**
 * Answers follow-up questions about one finished analysis.
 *
 * The chat is grounded in the analysis: the engine receives the capsule (the conversation or the
 * screenshot read), the state it derived, and the turns so far, so "why did you say that?" or
 * "draft something softer" can be answered without re-reading anything from scratch.
 */
interface AnalysisChatEngine {
    suspend fun chat(
        context: ContextCapsule,
        state: ConversationState,
        history: List<ChatTurn>,
        question: String
    ): String
}
