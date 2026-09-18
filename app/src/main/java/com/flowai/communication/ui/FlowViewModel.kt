package com.flowai.communication.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.flowai.communication.ai.LlmService
import com.flowai.communication.ai.MockLlmService
import com.flowai.communication.data.model.*
import com.flowai.communication.data.repository.ConversationRepository
import com.flowai.communication.domain.CaptureEndReason
import com.flowai.communication.domain.CaptureSession
import com.flowai.communication.domain.CaptureState
import com.flowai.communication.domain.ChatToActionEngine
import com.flowai.communication.domain.ConsumedShareStore
import com.flowai.communication.domain.ConversationStateBuilder
import com.flowai.communication.domain.InMemoryConsumedShareStore
import com.flowai.communication.domain.NextActionEngine
import com.flowai.communication.domain.PlainTextDialogueParser

enum class Page { HOME, INPUT, ANALYSIS, ACTION, SETTINGS }

/** Entry points where another app handed us the text, as opposed to the user typing it. */
private val EXTERNAL_SOURCES = setOf(SourceType.SHARE, SourceType.PROCESS_TEXT, SourceType.SCREENSHOT)

/**
 * Presents one engine through all three engine interfaces.
 *
 * The instance is kept while the configuration is unchanged, and rebuilt when it changes. That
 * matters for the API engine: its first call fetches the whole analysis and the later two read from
 * it, so handing each call a fresh instance would throw that result away and send it back to the
 * local fallback.
 */
private class SuspendingEngine(
    private val factory: () -> LlmService,
    private val signature: () -> String = { "" }
) : ConversationStateBuilder, NextActionEngine, ChatToActionEngine {

    private var current: LlmService? = null
    private var currentSignature: String? = null

    private fun engine(): LlmService {
        val now = signature()
        val existing = current
        if (existing != null && currentSignature == now) return existing
        return factory().also { current = it; currentSignature = now }
    }

    override suspend fun build(context: ContextCapsule): ConversationState = engine().build(context)

    override suspend fun recommend(state: ConversationState): List<NextAction> =
        engine().recommend(state)

    override suspend fun execute(
        context: ContextCapsule,
        state: ConversationState,
        action: NextAction
    ): ActionResult = engine().execute(context, state, action)
}

/** Sensitive state belongs to this in-memory session, never SavedStateHandle. */
class FlowViewModel(
    private val consumedShares: ConsumedShareStore = InMemoryConsumedShareStore(),
    /** Owns the Just-in-Time context lifecycle. Platform resources release in response to it. */
    val capture: CaptureSession = CaptureSession(),
    /**
     * Chooses the engine.
     *
     * Defaults to the local one so tests and unconfigured installs never touch the network; the app
     * passes a factory plus a signature that changes when the configuration does.
     */
    private val engineFactory: () -> LlmService = { MockLlmService() },
    private val engineSignature: () -> String = { "" }
) : ViewModel() {
    private val engines = SuspendingEngine(engineFactory, engineSignature)
    private val repository = ConversationRepository(
        PlainTextDialogueParser(),
        engines,
        engines,
        engines
    )
    var page by mutableStateOf(Page.HOME); private set
    var input by mutableStateOf(""); private set
    var analysis by mutableStateOf<AnalysisResult?>(null); private set
    var selected by mutableStateOf<NextAction?>(null); private set
    var output by mutableStateOf<ActionResult?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var clearedNotice by mutableStateOf<String?>(null); private set
    var sourceType by mutableStateOf(SourceType.TEXT); private set

    /**
     * True while an engine call is in flight.
     *
     * The engines may reach a network service, so the UI needs something to show between the tap
     * and the result.
     */
    var busy by mutableStateOf(false); private set

    /** Set when a new external payload replaced work the user had not finished. */
    var supersededNotice by mutableStateOf<String?>(null); private set

    /** Set when a capture ran but recognised nothing worth analysing. */
    var captureNotice by mutableStateOf<String?>(null); private set
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
     *
     * [allowSameText] exists for capture: re-reading the same screen is an explicit user action, so
     * it must not be swallowed by the "already delivered" guard.
     */
    fun consumeShare(text: String, source: SourceType = SourceType.SHARE, allowSameText: Boolean = false) {
        if (source !in EXTERNAL_SOURCES) return
        val consumed = consumedShares.wasConsumed(text)
        if ((text == lastSharedText || consumed) && !allowSameText) return
        lastSharedText = text
        consumedShares.markConsumed(text)
        openInput(text, source)
    }

    /** Called when a capture ran but produced no usable text. */
    fun reportCaptureEmpty() {
        captureNotice = "这次截屏没有识别到文字，请让聊天内容完整显示在屏幕上后重试"
    }

    /** Called when the capture pipeline could not run at all. */
    fun reportCaptureUnavailable(reason: String) {
        captureNotice = reason
    }

    /** Clears the capture notice once the user acknowledges it by moving on. */
    fun dismissCaptureNotice() { captureNotice = null }

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
        // Any "previously cleared" / "superseded" / capture notice is now stale.
        clearedNotice = null; supersededNotice = null; captureNotice = null
        // Engines may reach a network service, so this is asynchronous; the UI reads `busy` to show
        // progress rather than appearing to do nothing.
        busy = true
        viewModelScope.launch {
            try {
                analysis = repository.analyze(input, sourceType)
                page = Page.ANALYSIS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "分析失败，请重试"
                page = Page.INPUT
            } finally {
                busy = false
            }
        }
    }

    fun choose(action: NextAction) {
        val current = analysis ?: return
        if (action !in current.actions) return
        busy = true
        viewModelScope.launch {
            try {
                output = repository.execute(current, action)
                selected = action
                page = Page.ACTION
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "生成失败，请重试"
            } finally {
                busy = false
            }
        }
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
            // Settings is a side trip: leaving it returns home without touching the session.
            Page.SETTINGS, Page.HOME -> page = Page.HOME
        }
    }

    /** Opens the engine configuration screen. */
    fun openSettings() { page = Page.SETTINGS }

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
