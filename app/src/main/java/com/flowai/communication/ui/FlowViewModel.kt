package com.flowai.communication.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.flowai.communication.ai.MockLlmService
import com.flowai.communication.data.model.*
import com.flowai.communication.data.repository.ConversationRepository
import com.flowai.communication.domain.CaptureEndReason
import com.flowai.communication.domain.CaptureSession
import com.flowai.communication.domain.CaptureState
import com.flowai.communication.domain.ConsumedShareStore
import com.flowai.communication.domain.InMemoryConsumedShareStore
import com.flowai.communication.domain.PlainTextDialogueParser

enum class Page { HOME, INPUT, ANALYSIS, ACTION }

/** Entry points where another app handed us the text, as opposed to the user typing it. */
private val EXTERNAL_SOURCES = setOf(SourceType.SHARE, SourceType.PROCESS_TEXT, SourceType.SCREENSHOT)

/** Sensitive state belongs to this in-memory session, never SavedStateHandle. */
class FlowViewModel(
    private val consumedShares: ConsumedShareStore = InMemoryConsumedShareStore(),
    /** Owns the Just-in-Time context lifecycle. Platform resources release in response to it. */
    val capture: CaptureSession = CaptureSession()
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

    /** Set when a new external payload replaced work the user had not finished. */
    var supersededNotice by mutableStateOf<String?>(null); private set

    /** Mirrors [CaptureSession.state] for the UI (drives any "session active" indication). */
    val captureState: CaptureState get() = capture.state

    private var lastSharedText: String? = null

    fun edit(text: String) { input = text.take(20_000); error = null }

    fun openInput(text: String? = null, source: SourceType = SourceType.TEXT) {
        // A new payload always starts a new session; the previous one's context is released here.
        val superseded = capture.begin(text.orEmpty().take(20_000), source)
        val hadUnfinishedWork = page == Page.ANALYSIS || page == Page.ACTION || analysis != null
        supersededNotice = if (superseded == CaptureEndReason.SUPERSEDED && hadUnfinishedWork) {
            "上一段分析已被新的内容替换"
        } else null
        clearSession(clearNotice = false)
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

    /** Releases an over-deadline session. Callers release their platform resources when this is true. */
    fun releaseIfExpired(now: Long = System.currentTimeMillis()): Boolean {
        val expired = capture.onExpired(now)
        if (expired) {
            clearSession(clearNotice = false)
            clearedNotice = "本次内容已超时清除"
        }
        return expired
    }

    fun analyze() {
        // Drop old results even if analysis of the new input fails.
        analysis = null; selected = null; output = null; error = null
        // Any "previously cleared" / "superseded" notice is now stale.
        clearedNotice = null; supersededNotice = null
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
        capture.end(CaptureEndReason.USER_ENDED)
        clearSession()
        clearedNotice = "本次内容已清除"
    }

    private fun clearSession(clearNotice: Boolean = true) {
        input = ""
        analysis = null
        selected = null
        output = null
        error = null
        if (clearNotice) clearedNotice = null
        sourceType = SourceType.TEXT
        page = Page.HOME
    }

    override fun onCleared() {
        // Releasing the session here is what guarantees context does not outlive the ViewModel.
        capture.end(CaptureEndReason.USER_ENDED)
        clearSession()
        super.onCleared()
    }
}
