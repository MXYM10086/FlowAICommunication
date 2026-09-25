package com.flowai.communication.system

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flowai.communication.ai.EngineSettingsStore
import com.flowai.communication.ai.LlmService
import com.flowai.communication.data.model.*
import com.flowai.communication.data.repository.ConversationRepository
import com.flowai.communication.domain.AnalysisChatEngine
import com.flowai.communication.domain.ChatRole
import com.flowai.communication.domain.ChatToActionEngine
import com.flowai.communication.domain.ChatTurn
import com.flowai.communication.domain.ConversationStateBuilder
import com.flowai.communication.domain.NextActionEngine
import com.flowai.communication.domain.PlainTextDialogueParser
import com.flowai.communication.ui.components.FlowTheme
import com.flowai.communication.ui.panel.AssistantPanel
import com.flowai.communication.ui.panel.AssistantPanelState
import com.flowai.communication.ui.panel.ExecutedAction
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The in-place assistant window.
 *
 * This is the product's "assistant inside the chat app" surface: it floats above whatever the user
 * is looking at, takes text the user pastes or shares, and shows the analysis without switching
 * apps. Screen capture is strictly user-triggered: the panel hides itself first so it never ends up
 * in the frame, and the frame is analysed where it lands ([FrameAnalysis]) with the picture
 * released straight after.
 *
 * Compose inside a WindowManager overlay needs its own lifecycle/saved-state owners; that plumbing
 * lives here so the panel itself stays a plain composable.
 */
class AssistantPanelWindow(
    private val context: Context,
    private val onClose: () -> Unit,
    /** Invoked when the user asks for a capture; the host starts the capture activity. */
    private val onStartCapture: (() -> Unit)? = null
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: android.view.View? = null

    /** Repository is shared with the app's flow so results stay identical. */
    private val engine = ConfigurableEngine(context)
    private val repository = ConversationRepository(PlainTextDialogueParser(), engine, engine, engine, engine)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /**
     * Window params are rebuilt for every attach.
     *
     * Reusing the same LayoutParams after `removeView` re-attached the panel with the *bubble's*
     * geometry (observed on device as `(1230,1002)(wrapxwrap)`), so the panel was added as a tiny
     * off-screen box: `dumpsys` showed a visible window while nothing was drawn.
     */
    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // Overlay type: sits above the chat app without modifying it.
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable is required so the user can type/paste into the panel; the window is only as
            // large as its content so the app underneath stays visible around it.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Resize rather than pan. Panning moved the whole panel up when the keyboard opened,
            // pushing its lower controls out of reach on tall screens.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

    /** Kept so a suspended panel can be re-attached with its state intact. */
    private var rootView: android.view.View? = null

    /**
     * Height allowance as a fraction of the screen height.
     *
     * A ratio rather than a dp constant, so the panel scales across devices instead of relying on a
     * number chosen for one phone.
     */
    private val maxPanelHeightFraction = DEFAULT_MAX_HEIGHT_FRACTION

    /** The fraction above, converted to dp against the current display. */
    private val maxPanelHeightDp: Dp
        get() {
            val metrics = context.resources.displayMetrics
            return (metrics.heightPixels / metrics.density * maxPanelHeightFraction).dp
        }

    /** True while the panel is off screen but still alive. */
    private var suspended = false

    /** Lets the pet ignore taps while a capture it started is still in flight. */
    val isSuspended: Boolean get() = suspended

    /** Set by a capture that is in flight / has returned, and read by the panel. */
    private val capturedText = mutableStateOf<String?>(null)
    private val captureFailure = mutableStateOf<String?>(null)

    /** Text the panel should open with next time it is shown (e.g. after a capture). */
    private var pendingInitialText: String? = null

    /**
     * Takes the panel off screen while a capture runs.
     *
     * The window must not be in the frame, so the view is detached. Its *state* is kept in
     * [panelState], which outlives the view.
     */
    fun suspendPanel() {
        val current = view ?: return
        runCatching { windowManager.removeViewImmediate(current) }
            .onFailure { e -> Log.e(TAG, "removeView failed", e) }
        view = null
        rootView = null
        suspended = true
        Log.i(TAG, "assistant panel suspended")
    }

    /**
     * Restores the panel after a capture by rebuilding the view, not by re-attaching the old one.
     *
     * Re-adding a detached view produced a panel that rendered but no longer responded to touches:
     * tapping any control did nothing. Rebuilding costs one view hierarchy and keeps the user's
     * text, which lives in [panelState].
     */
    fun resumePanel() {
        if (!suspended) return
        suspended = false
        attach()
        Log.i(TAG, "assistant panel resumed (rebuilt)")
    }

    /**
     * Puts [text] into the input box, showing the panel if needed.
     *
     * Used by the selection toolbar: the user picked text elsewhere and expects the panel to come
     * up already holding it, not to be asked to paste.
     */
    fun showWithText(text: String) {
        panelState.input = text.take(MAX_INPUT_CHARS)
        panelState.result = null
        panelState.chosen = null
        panelState.executed = null
        panelState.error = null
        panelState.copied = null
        if (view == null) {
            if (suspended) resumePanel() else show()
        }
    }

    fun show(initialText: String? = null) {
        if (view != null) return
        val seed = initialText ?: pendingInitialText
        if (!seed.isNullOrBlank() && panelState.input.isBlank()) {
            panelState.input = seed
        }
        pendingInitialText = null
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        attach()
        Log.i(TAG, "assistant panel shown")
    }

    /** Builds the view hierarchy and attaches it to the window. */
    private fun attach() {
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@AssistantPanelWindow)
            setViewTreeViewModelStoreOwner(this@AssistantPanelWindow)
            setViewTreeSavedStateRegistryOwner(this@AssistantPanelWindow)
            setContent { FlowTheme { PanelContent(panelState, maxPanelHeightDp) } }
        }

        // ComposeView is final, so outside touches are caught by a wrapper instead of a subclass.
        val root = OutsideTouchFrameLayout(context, onOutsideTouch = { destroy() }).apply {
            addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            setViewTreeLifecycleOwner(this@AssistantPanelWindow)
            setViewTreeViewModelStoreOwner(this@AssistantPanelWindow)
            setViewTreeSavedStateRegistryOwner(this@AssistantPanelWindow)
        }

        runCatching { windowManager.addView(root, buildLayoutParams()) }
            .onFailure {
                Log.e(TAG, "could not add assistant panel", it)
                destroy()
                return
            }
        view = root
        rootView = root
    }

    fun hide() {
        view?.let {
            runCatching { windowManager.removeViewImmediate(it) }
                .onFailure { e -> Log.e(TAG, "removeView failed", e) }
        }
        view = null
        rootView = null
        suspended = false
    }

    fun destroy() {
        hide()
        store.clear()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        onClose()
    }

    /**
     * Survives the view being rebuilt around a capture, so the user's text and results are kept.
     */
    private val panelState = AssistantPanelState()

    @Composable
    private fun PanelContent(state: AssistantPanelState, maxHeight: Dp) {
        AssistantPanel(
            state = state,
            maxHeight = maxHeight,
            analyze = { text ->
                runCatching { repository.analyze(text, SourceType.TEXT) }.getOrNull()
            },
            execute = { analysis, action -> runExecute(analysis, action) },
            onClose = { destroy() },
            onOpenApp = { openFullApp() },
            // Must go through beginCapture so the panel hides itself before the frame is taken.
            onCapture = { beginCapture() },
            capturedText = capturedText.value,
            captureFailure = captureFailure.value,
            onChat = ::sendChat
        )
    }

    /** One action executed against one analysis; null when the engine call fails. */
    private suspend fun runExecute(analysis: AnalysisResult, action: NextAction): ExecutedAction? =
        runCatching { repository.execute(analysis, action) }
            .map { ExecutedAction(action.id, it.replies, it.objects, it.note) }
            .getOrNull()

    /**
     * A follow-up question about the analysis the panel is showing, answered with that analysis
     * as grounding. Failures land as a turn, like in the full app.
     */
    private fun sendChat(text: String) {
        val current = panelState.result ?: return
        val question = text.trim()
        if (question.isEmpty() || panelState.chatBusy) return
        val previous = panelState.chat
        panelState.chat = previous + ChatTurn(ChatRole.USER, question)
        panelState.chatBusy = true
        lifecycleScope.launch {
            try {
                val reply = repository.chat(current, previous, question)
                panelState.chat = panelState.chat + ChatTurn(ChatRole.ASSISTANT, reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                panelState.chat = panelState.chat +
                    ChatTurn(ChatRole.ASSISTANT, "回复失败：${e.message ?: "请重试"}")
            } finally {
                panelState.chatBusy = false
            }
        }
    }

    /**
     * Hides the panel and asks for a capture.
     *
     * The panel must be hidden first: it is an overlay window, so it would otherwise appear in the
     * captured frame and be recognised as chat text.
     *
     * The panel's button calls this, not [onStartCapture] directly — wiring the raw callback caused
     * the panel to stay on screen inside the capture.
     */
    fun beginCapture() {
        // Only detach the view: the object (and the user's typed text) must survive the consent
        // dialogs, which can outlive the panel's window by a long way.
        suspendPanel()
        captureFailure.value = null
        onStartCapture?.invoke()
    }

    /** Called when a capture returns, so the panel can be shown again with the text in place. */
    fun deliverCapture(text: String?, failure: String?) {
        Log.i(TAG, "panel deliverCapture: chars=${text?.length ?: 0} suspended=$suspended")
        capturedText.value = text
        captureFailure.value = failure
        pendingInitialText = text
        if (suspended) resumePanel() else show()
        Log.i(TAG, "panel visible after capture: ${view != null}")
    }

    /**
     * Called when a capture returns a framed picture: the panel reads it and shows the result
     * itself, over the app the user was reading.
     *
     * The frame's text is recognised on device and analysed ([FrameAnalysis]), and the top
     * recommended action runs right away — the point of the one-tap flow is that copy-ready reply
     * drafts are waiting when the panel comes back, not another two taps down.
     */
    fun deliverCaptureImage(path: String) {
        Log.i(TAG, "panel deliverCaptureImage: suspended=$suspended")
        capturedText.value = null
        captureFailure.value = null
        panelState.result = null
        panelState.chosen = null
        panelState.executed = null
        panelState.chat = emptyList()
        panelState.copied = null
        panelState.error = null
        panelState.analyzing = true
        if (suspended) resumePanel() else show()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull().also {
                    runCatching { File(path).delete() }
                }
            }
            if (bitmap == null) {
                panelState.analyzing = false
                panelState.error = "截屏读取失败，请重新截取"
                return@launch
            }
            try {
                val result = FrameAnalysis.analyze(repository, bitmap)
                panelState.result = result
                result.actions.firstOrNull()?.let { action ->
                    panelState.chosen = action
                    panelState.executed = runExecute(result, action)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                panelState.error = e.message ?: "分析失败，请重试"
            } finally {
                bitmap.recycle()
                panelState.analyzing = false
            }
        }
    }

    /** Escape hatch: the panel is a summary, the full app has everything. */
    /**
     * Escape hatch: the panel is a summary, the full app has everything.
     *
     * The panel is closed first — it is an overlay, so leaving it up would cover the app the user
     * just asked to see.
     */
    private fun openFullApp() {
        hide()
        runCatching {
            context.startActivity(
                android.content.Intent(context, com.flowai.communication.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "could not open full app", it) }
    }

    private companion object {
        const val TAG = "FlowAI"

        /**
         * Share of the screen height the panel may occupy.
         *
         * Leaves the content underneath visible — the point of an in-place assistant — while giving
         * long analyses room to breathe instead of a fixed dp cap tuned to one device.
         */
        const val DEFAULT_MAX_HEIGHT_FRACTION = 0.68f

        /** Matches the cap the app and the repository enforce on pasted chat text. */
        const val MAX_INPUT_CHARS = 20_000
    }
}

/**
 * Presents the configured engine through all three engine interfaces.
 *
 * Keeps one instance while the configuration is unchanged, and rebuilds it when the settings
 * change. That matters for the API engine, whose first call fetches the whole analysis and whose
 * later two read from it — a fresh instance per call would discard that and fall back to local.
 */
private class ConfigurableEngine(context: Context) :
    ConversationStateBuilder, NextActionEngine, ChatToActionEngine, AnalysisChatEngine {

    private val engineFactory = EngineSettingsStore.engineFactory(context)
    private val factory = engineFactory.first
    private val signature = engineFactory.second
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

/**
 * FrameLayout that also reports touches landing outside the panel.
 *
 * [WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH] only delivers those touches to the view's
 * `onTouchEvent` as ACTION_OUTSIDE — declaring the flag without handling it does nothing, which is
 * why tapping beside the panel used to leave it stuck on screen.
 */
private class OutsideTouchFrameLayout(
    context: Context,
    private val onOutsideTouch: () -> Unit = {}
) : FrameLayout(context) {

    /** Constructor the tooling expects; the panel uses the callback form above. */
    @Suppress("unused")
    constructor(context: Context, attrs: android.util.AttributeSet?) : this(context)
    @Suppress("unused")
    constructor(context: Context, attrs: android.util.AttributeSet?, defStyleAttr: Int) : this(context)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch()
            // Report the interaction so accessibility services see a click, not a swallowed touch.
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
