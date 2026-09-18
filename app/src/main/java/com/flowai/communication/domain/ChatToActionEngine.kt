package com.flowai.communication.domain
import com.flowai.communication.data.model.*

/**
 * Carries out a chosen action: draft replies, extract tasks and events.
 *
 * Suspending so a network-backed engine fits the same seam as the local one.
 */
interface ChatToActionEngine {
    suspend fun execute(
        context: ContextCapsule,
        state: ConversationState,
        action: NextAction
    ): ActionResult
}
