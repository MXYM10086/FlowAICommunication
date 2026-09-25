package com.flowai.communication.domain

/** Who wrote one line of the follow-up conversation kept with an analysis. */
enum class ChatRole { USER, ASSISTANT }

/**
 * One turn of the per-analysis follow-up conversation.
 *
 * Lives only in the session, next to the analysis it belongs to: leaving the workflow or ending
 * the session drops it, exactly like the conversation and the analysis themselves.
 */
data class ChatTurn(val role: ChatRole, val text: String)
