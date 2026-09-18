package com.flowai.communication.data.model
data class NextAction(
    val id: String, val title: String, val description: String,
    val type: ActionType, val reason: String, val priority: Int
)
enum class ActionType {
    CLARIFY, REPORT_PROGRESS, GIVE_DEADLINE, SOFTEN_TONE, DECLINE_POLITELY,
    SUMMARIZE, EXTRACT_TASK, CREATE_EVENT, CREATE_REMINDER, OTHER
}
