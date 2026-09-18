package com.flowai.communication.domain
import com.flowai.communication.data.model.*
interface ConversationStateBuilder { fun build(context: ContextCapsule): ConversationState }
