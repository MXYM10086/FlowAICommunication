package com.flowai.communication.system

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.runtime.Composable
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
    private val onClose: () -> Unit
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null

    /** Repository is shared with the app's flow so results stay identical. */
    private val mock = MockLlmService()
    private val repository = ConversationRepository(PlainTextDialogueParser(), mock, mock, mock)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var params: WindowManager.LayoutParams? = null

    fun show(initialText: String? = null) {
        if (view != null) return
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@AssistantPanelWindow)
            setViewTreeViewModelStoreOwner(this@AssistantPanelWindow)
            setViewTreeSavedStateRegistryOwner(this@AssistantPanelWindow)
            setContent { FlowTheme { PanelContent(initialText) } }
        }

        val layoutParams = WindowManager.LayoutParams(
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

        runCatching { windowManager.addView(composeView, layoutParams) }
            .onFailure {
                Log.e(TAG, "could not add assistant panel", it)
                destroy()
                return
            }
        view = composeView
        params = layoutParams
        Log.i(TAG, "assistant panel shown")
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        params = null
        Log.i(TAG, "assistant panel hidden")
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
            onOpenApp = { openFullApp() }
        )
    }

    /** Escape hatch: the panel is a summary, the full app has everything. */
    private fun openFullApp() {
        runCatching {
            context.startActivity(
                android.content.Intent(context, com.flowai.communication.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private companion object {
        const val TAG = "FlowAI"
    }
}
