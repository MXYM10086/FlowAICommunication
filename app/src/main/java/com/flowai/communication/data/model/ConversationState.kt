package com.flowai.communication.data.model
data class ConversationState(
    val topic: String,
    val participantGoals: List<String>,
    val agreements: List<String>,
    val disagreements: List<String>,
    val unresolvedIssues: List<String>,
    val communicationSignals: List<String>,
    val keyFacts: List<String>,
    val stage: ConversationStage,
    val confidenceNote: String? = null
)
enum class ConversationStage { OPENING, DISCUSSION, NEGOTIATION, DECISION, FOLLOW_UP, CLOSING, UNKNOWN }
