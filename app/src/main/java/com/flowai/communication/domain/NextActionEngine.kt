package com.flowai.communication.domain
import com.flowai.communication.data.model.*

/**
 * Proposes what to do next, given the understood state.
 *
 * Suspending so a network-backed engine fits the same seam as the local one.
 */
interface NextActionEngine {
    suspend fun recommend(state: ConversationState): List<NextAction>
}
