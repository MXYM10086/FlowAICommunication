package com.flowai.communication.ai
import com.flowai.communication.domain.*
/** Replaceable boundary; MVP has only a local deterministic implementation. */
interface LlmService : ConversationStateBuilder, NextActionEngine, ChatToActionEngine
