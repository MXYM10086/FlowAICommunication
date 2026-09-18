package com.flowai.communication.domain
import com.flowai.communication.data.model.*
interface NextActionEngine { fun recommend(state: ConversationState): List<NextAction> }
