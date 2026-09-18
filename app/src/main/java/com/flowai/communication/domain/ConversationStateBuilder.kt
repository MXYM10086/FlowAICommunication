package com.flowai.communication.domain
import com.flowai.communication.data.model.*

/**
 * Turns a user-supplied conversation into structured understanding.
 *
 * Suspending because a real implementation has to reach a network service; the local deterministic
 * engine simply never suspends.
 */
interface ConversationStateBuilder {
    suspend fun build(context: ContextCapsule): ConversationState
}
