package com.flowai.communication.data.model
data class ContextCapsule(
    val sourceType: SourceType,
    val appName: String? = null,
    val messages: List<Message>,
    val rawText: String? = null,
    /**
     * A captured screenshot, base64-encoded PNG, for engines that read the image themselves.
     *
     * When present the conversation is *in* the picture: [messages] stays empty because there is
     * no local text to parse, and only a vision-capable remote engine can build a state from it.
     * A credential-adjacent payload like [rawText] — session memory only, never persisted.
     */
    val imageBase64: String? = null,
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
