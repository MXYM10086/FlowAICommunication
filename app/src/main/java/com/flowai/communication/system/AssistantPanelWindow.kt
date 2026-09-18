package com.flowai.communication.system

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flowai.communication.ai.MockLlmService
import com.flowai.communication.data.model.SourceType
import com.flowai.communication.data.repository.ConversationRepository
import com.flowai.communication.domain.PlainTextDialogueParser
import com.flowai.communication.ui.components.FlowTheme
import com.flowai.communication.ui.panel.AssistantPanel
import com.flowai.communication.ui.panel.ExecutedAction

/**
 * The in-place assistant window.
 *
 * This is the product's "assistant inside the chat app" surface: it floats above whatever the user
 * is looking at, takes text the user pastes or shares, and shows the analysis without switching
 * apps. It deliberately does not read the screen — with `screen_share_protection` on (HyperOS
 * default), capture of the chat app returns black frames, and silently reading another app's
 * content would contradict the product's Just-in-Time Context rule anyway.
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
    private val mock = MockLlmService()
    private val repository = ConversationRepository(PlainTextDialogueParser(), mock, mock, mock)

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
        }

    /** Kept so a suspended panel can be re-attached with its state intact. */
    private var rootView: android.view.View? = null

    /** True while the panel is off screen but still alive. */
    private var suspended = false

    /** Set by a capture that is in flight / has returned, and read by the panel. */
    private val capturedText = mutableStateOf<String?>(null)
    private val captureFailure = mutableStateOf<String?>(null)

    /** Text the panel should open with next time it is shown (e.g. after a capture). */
    private var pendingInitialText: String? = null

    /**
     * Takes the panel off screen but keeps its object, Compose state and lifecycle intact.
     *
     * Used around a capture: the panel must not appear in the frame, but tearing it down and
     * rebuilding it means the consent dialogs happen while the panel's state has nowhere to live,
     * and the text the user was working with is lost. Suspending only detaches the view.
     */
    fun suspendPanel() {
        val current = view ?: return
        runCatching { windowManager.removeViewImmediate(current) }
            .onFailure { e -> Log.e(TAG, "removeView failed", e) }
        view = null
        suspended = true
        Log.i(TAG, "assistant panel suspended")
    }

    /** Re-attaches a suspended panel, preserving its state. */
    fun resumePanel() {
        if (!suspended) return
        suspended = false
        val root = rootView ?: return
        runCatching { windowManager.addView(root, buildLayoutParams()) }
            .onFailure {
                Log.e(TAG, "could not re-add assistant panel", it)
                return
            }
        view = root
        Log.i(TAG, "assistant panel resumed")
    }

    fun show(initialText: String? = null) {
        if (view != null) return
        val seed = initialText ?: pendingInitialText
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@AssistantPanelWindow)
            setViewTreeViewModelStoreOwner(this@AssistantPanelWindow)
            setViewTreeSavedStateRegistryOwner(this@AssistantPanelWindow)
            setContent { FlowTheme { PanelContent(seed) } }
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
        Log.i(TAG, "assistant panel shown")
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

    @Composable
    private fun PanelContent(initialText: String?) {
        AssistantPanel(
            initialText = initialText,
            analyze = { text ->
                runCatching { repository.analyze(text, SourceType.TEXT) }.getOrNull()
            },
            execute = { analysis, action ->
                runCatching { repository.execute(analysis, action) }
                    .map { ExecutedAction(action.id, it.replies, it.objects, it.note) }
                    .getOrNull()
            },
            onClose = { destroy() },
            onOpenApp = { openFullApp() },
            // Must go through beginCapture so the panel hides itself before the frame is taken.
            onCapture = { beginCapture() },
            capturedText = capturedText.value,
            captureFailure = captureFailure.value
        )
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
    }
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
