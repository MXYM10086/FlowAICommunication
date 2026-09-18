package com.flowai.communication.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.flowai.communication.ai.MockLlmService
import com.flowai.communication.data.model.*
import com.flowai.communication.data.repository.ConversationRepository
import com.flowai.communication.domain.ConsumedShareStore
import com.flowai.communication.domain.InMemoryConsumedShareStore
import com.flowai.communication.domain.PlainTextDialogueParser

enum class Page { HOME, INPUT, ANALYSIS, ACTION }

/** Entry points where another app handed us the text, as opposed to the user typing it. */
private val EXTERNAL_SOURCES = setOf(SourceType.SHARE, SourceType.PROCESS_TEXT)

/** Sensitive state belongs to this in-memory session, never SavedStateHandle. */
class FlowViewModel(
    private val consumedShares: ConsumedShareStore = InMemoryConsumedShareStore()
) : ViewModel() {
    private val mock = MockLlmService()
    private val repository = ConversationRepository(PlainTextDialogueParser(), mock, mock, mock)
    var page by mutableStateOf(Page.HOME); private set
    var input by mutableStateOf(""); private set
    var analysis by mutableStateOf<AnalysisResult?>(null); private set
    var selected by mutableStateOf<NextAction?>(null); private set
    var output by mutableStateOf<ActionResult?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var clearedNotice by mutableStateOf<String?>(null); private set
    var sourceType by mutableStateOf(SourceType.TEXT); private set
    private var lastSharedText: String? = null

    fun edit(text: String) { input = text.take(20_000); error = null }

    fun openInput(text: String? = null, source: SourceType = SourceType.TEXT) {
        clearSession()
        sourceType = source
        input = text.orEmpty().take(20_000)
        page = Page.INPUT
    }

    /**
     * Called once per externally delivered text; a re-delivered identical payload is ignored.
     * This covers rotation and — via a process-surviving store — task recreation after process
     * death, where Android re-delivers the original intent from the task record.
     */
    fun consumeShare(text: String, source: SourceType = SourceType.SHARE) {
        if (source !in EXTERNAL_SOURCES) return
        if (text == lastSharedText) return
        if (consumedShares.wasConsumed(text)) return
        lastSharedText = text
        consumedShares.markConsumed(text)
        openInput(text, source)
    }

    fun analyze() {
        // Drop old results even if analysis of the new input fails.
        analysis = null; selected = null; output = null; error = null
        runCatching { repository.analyze(input, sourceType) }.onSuccess {
            analysis = it
            page = Page.ANALYSIS
        }.onFailure {
            error = it.message ?: "分析失败，请重试"
            page = Page.INPUT
        }
    }

    fun choose(action: NextAction) {
        val current = analysis ?: return
        if (action !in current.actions) return
        output = repository.execute(current, action)
        selected = action
        page = Page.ACTION
    }

    fun editReply(style: String, text: String) {
        val current = output ?: return
        output = current.copy(replies = current.replies.map {
            if (it.style == style) it.copy(text = text.take(20_000)) else it
        })
    }

    /** Returning within the workflow is not the same as ending it. */
    fun back() {
        when (page) {
            Page.ACTION -> { selected = null; output = null; page = Page.ANALYSIS }
            Page.INPUT, Page.ANALYSIS -> endSession()
            Page.HOME -> Unit
        }
    }

    fun endSession() {
        clearSession()
        clearedNotice = "本次内容已清除"
    }

    private fun clearSession() {
        input = ""
        analysis = null
        selected = null
        output = null
        error = null
        clearedNotice = null
        sourceType = SourceType.TEXT
        page = Page.HOME
    }

    override fun onCleared() {
        clearSession()
        super.onCleared()
    }
}
