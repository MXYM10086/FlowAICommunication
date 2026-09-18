package com.flowai.communication.data.model
sealed interface ActionObject {
    data class Task(val title: String, val assignee: String?, val deadline: String?, val detail: String?) : ActionObject
    data class Event(val title: String, val time: String?, val location: String?, val participants: List<String>) : ActionObject
    data class Decision(val content: String) : ActionObject
}
data class ReplyCandidate(val style: String, val text: String)
data class ActionResult(
    val replies: List<ReplyCandidate> = emptyList(),
    val objects: List<ActionObject> = emptyList(),
    val note: String
)
data class AnalysisResult(
    val capsule: ContextCapsule,
    val state: ConversationState,
    val actions: List<NextAction>
)
