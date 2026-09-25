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
import com.flowai.communication.domain.AnalysisChatEngine
import com.flowai.communication.domain.CaptureEndReason
import com.flowai.communication.domain.CaptureSession
import com.flowai.communication.domain.CaptureState
import com.flowai.communication.domain.ChatRole
import com.flowai.communication.domain.ChatTurn
import com.flowai.communication.domain.ChatToActionEngine
import com.flowai.communication.domain.ConsumedShareStore
import com.flowai.communication.domain.ConversationStateBuilder
import com.flowai.communication.domain.InMemoryConsumedShareStore
import com.flowai.communication.domain.NextActionEngine
import com.flowai.communication.domain.PlainTextDialogueParser
import com.flowai.communication.system.FrameAnalysis

/** Entry points where another app handed us the text, as opposed to the user typing it. */
private val EXTERNAL_SOURCES = setOf(SourceType.SHARE, SourceType.PROCESS_TEXT, SourceType.SCREENSHOT)

/**
 * Presents one engine through all four engine interfaces, follow-up chat included.
 *
 * The instance is kept while the configuration is unchanged, and rebuilt when it changes. That
 * matters for the API engine: its first call fetches the whole analysis and the later two read from
 * it, so handing each call a fresh instance would throw that result away and send it back to the
 * local fallback.
 */
private class SuspendingEngine(
    private val factory: () -> LlmService,
    private val signature: () -> String = { "" }
) : ConversationStateBuilder, NextActionEngine, ChatToActionEngine, AnalysisChatEngine {

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

    override suspend fun chat(
        context: ContextCapsule,
        state: ConversationState,
        history: List<ChatTurn>,
        question: String
    ): String = engine().chat(context, state, history, question)
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
    private val engineSignature: () -> String = { "" },
    /** Persists the chosen reply persona across restarts; null keeps the default in tests. */
    private val replyStyles: com.flowai.communication.ai.ReplyStyleStore? = null
) : ViewModel() {
    private val engines = SuspendingEngine(engineFactory, engineSignature)
    private val repository = ConversationRepository(
        PlainTextDialogueParser(),
        engines,
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
     * Recognised capture text awaiting confirmation on [Page.OCR_PREVIEW], editable there.
     *
     * Lives only while that page is up: submitted or left, it is cleared, and it is never written
     * to disk — a misread chat is as sensitive as the chat itself. It survives configuration
     * changes because the ViewModel does.
     */
    var ocrDraft by mutableStateOf(""); private set

    /**
     * The follow-up conversation about the analysis on screen.
     *
     * Belongs to the session exactly like the analysis: ending it or returning home drops the
     * turns, and nothing is persisted — a question about a chat is as sensitive as the chat.
     */
    var chat by mutableStateOf<List<ChatTurn>>(emptyList()); private set

    /** True while a follow-up question is in flight. */
    var chatBusy by mutableStateOf(false); private set

    /** The persona drafts are written in; restored from preferences on start. */
    var replyStyle by mutableStateOf(replyStyles?.load()
        ?: com.flowai.communication.ai.ReplyStyle.WARM); private set

    /** Switches the persona and remembers the choice for the next app start. */
    fun selectReplyStyle(style: com.flowai.communication.ai.ReplyStyle) {
        if (style == replyStyle) return
        replyStyle = style
        replyStyles?.save(style)
    }

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

    /**
     * Hands recognised capture text to the preview step instead of straight into a session.
     *
     * Only the in-app capture button lands here; share and text-selection payloads keep going
     * through [consumeShare], where the text is already exactly what the user chose to hand over.
     */
    fun showOcrPreview(text: String) {
        ocrDraft = text.take(20_000)
        page = Page.OCR_PREVIEW
    }

    /** Applies the user's corrections while they edit on [Page.OCR_PREVIEW]. */
    fun editOcrDraft(text: String) { ocrDraft = text.take(20_000) }

    /**
     * Confirmation on the preview page: the draft becomes a normal screenshot session and is
     * analysed right away, so confirming costs one tap rather than "confirm, then analyse".
     */
    fun submitOcrDraft() {
        val text = ocrDraft
        // The draft's page is left here, so the draft itself goes with it.
        ocrDraft = ""
        openInput(text, SourceType.SCREENSHOT)
        analyze()
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

    /**
     * Capture path: the framed screenshot is recognised on device, then the text is analysed.
     *
     * No preview step — [FrameAnalysis] reads the conversation text off the frame itself and the
     * analysis of that text goes straight to [Page.ANALYSIS]. The bitmap is read on the IO
     * dispatcher and recycled once the analysis settled either way.
     */
    fun analyzeScreenshot(bitmap: android.graphics.Bitmap) {
        val superseded = capture.begin("", SourceType.SCREENSHOT)
        val hadUnfinishedWork = page == Page.ANALYSIS || page == Page.ACTION || analysis != null
        supersededNotice = if (superseded == CaptureEndReason.SUPERSEDED && hadUnfinishedWork) {
            "上一段分析已被新的内容替换"
        } else null
        clearSession(clearNotice = false)
        sourceType = SourceType.SCREENSHOT
        busy = true
        viewModelScope.launch {
            try {
                analysis = FrameAnalysis.analyze(repository, bitmap)
                page = Page.ANALYSIS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // There is no input page in this flow to carry `error`; the home screen's capture
                // notice is where a failed read belongs, so the user sees why nothing appeared.
                captureNotice = e.message ?: "识别分析失败，请重试"
                page = Page.HOME
            } finally {
                bitmap.recycle()
                busy = false
            }
        }
    }

    /** Opens the follow-up conversation for the analysis on screen. */
    fun openChat() { page = Page.CHAT }

    /**
     * Appends the question, then the model's answer, keeping the whole exchange as context so a
     * follow-up can build on the previous one. Failures land as a turn too — a vanished error
     * would look like the model ignoring the user.
     */
    fun sendChat(text: String) {
        val current = analysis ?: return
        val question = text.trim()
        if (question.isEmpty() || chatBusy) return
        // The question joins the visible history at once; the engine gets the turns *before* it,
        // since it appends the question itself — passing both would send the question twice.
        val previous = chat
        chat = chat + ChatTurn(ChatRole.USER, question)
        chatBusy = true
        viewModelScope.launch {
            try {
                val reply = repository.chat(current, previous, question)
                chat = chat + ChatTurn(ChatRole.ASSISTANT, reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chat = chat + ChatTurn(ChatRole.ASSISTANT, "回复失败：${e.message ?: "请重试"}")
            } finally {
                chatBusy = false
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
            // The chat hangs off the analysis: leaving it returns there, keeping the turns.
            Page.CHAT -> page = Page.ANALYSIS
            Page.INPUT, Page.ANALYSIS -> endSession()
            // Leaving the preview discards the draft: it is a capture scratchpad, and no session
            // has started yet, so there is nothing for endSession to clear.
            Page.OCR_PREVIEW -> { ocrDraft = ""; page = Page.HOME }
            // Settings, the desk-pet picker and the help page are side trips: leaving them returns
            // home without touching the session.
            Page.SETTINGS, Page.SKINS, Page.HELP, Page.HOME -> page = Page.HOME
        }
    }

    /** Opens the engine configuration screen. */
    fun openSettings() { page = Page.SETTINGS }

    /** Opens the desk-pet skin picker. */
    fun openSkins() { page = Page.SKINS }

    /** Opens the help page that holds the home page's explanatory overflow. */
    fun openHelp() { page = Page.HELP }

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
        chat = emptyList()
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
