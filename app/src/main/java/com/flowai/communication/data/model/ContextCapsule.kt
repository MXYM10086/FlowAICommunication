package com.flowai.communication.data.model
data class ContextCapsule(
    val sourceType: SourceType,
    val appName: String? = null,
    val messages: List<Message>,
    val rawText: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
enum class SourceType {
    TEXT,
    SCREENSHOT,
    /** Another app shared text into FlowAI (ACTION_SEND). */
    SHARE,
    /** User selected text in another app and picked FlowAI from the selection toolbar (ACTION_PROCESS_TEXT). */
    PROCESS_TEXT,
    ACCESSIBILITY
}
