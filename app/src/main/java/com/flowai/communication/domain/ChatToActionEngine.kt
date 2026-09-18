package com.flowai.communication.domain
import com.flowai.communication.data.model.*
interface ChatToActionEngine { fun execute(context: ContextCapsule, state: ConversationState, action: NextAction): ActionResult }
