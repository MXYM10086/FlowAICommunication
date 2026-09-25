package com.flowai.communication

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flowai.communication.data.model.SourceType
import com.flowai.communication.domain.CaptureFailure
import com.flowai.communication.domain.PrefsConsumedShareStore
import com.flowai.communication.domain.SharedText
import com.flowai.communication.system.CaptureDispatch
import com.flowai.communication.system.CaptureForPanelActivity
import com.flowai.communication.system.FloatingAssistantService
import com.flowai.communication.system.InstantCaptureActivity
import com.flowai.communication.ui.*
import com.flowai.communication.ui.home.*
import com.flowai.communication.ui.ocr.OcrPreviewScreen
import com.flowai.communication.ui.analysis.AnalysisScreen
import com.flowai.communication.ui.action.ActionScreen
import com.flowai.communication.ui.chat.ChatScreen
import com.flowai.communication.ui.components.EngineBadge
import com.flowai.communication.ui.components.FlowTheme
import com.flowai.communication.ui.help.HelpScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Extra that opens the assistant panel directly; used for testing and from the home screen. */
private const val EXTRA_SHOW_ASSISTANT = "show_assistant"

/** adb logcat -s FlowAI */
private const val TAG = "FlowAI"

/**
 * One finished capture-chain run handed to the composition.
 *
 * A plain class on purpose: [FlowApp] keys a LaunchedEffect on it, and two captures can carry
 * identical text (or the same failure message), so structural equality would swallow the second.
 *
 * [imagePath] points at the staged frame PNG when the capture went the framed-image route; the
 * panel decodes it once, recognises its text on device, and deletes the file right away.
 */
private class CaptureDelivery(val text: String?, val failure: String?, val imagePath: String? = null)

/**
 * Guidance string for each capture failure reason.
 *
 * Shared by every capture entry point (in-app, panel, bubble) so the same failure always reads the
 * same way. Kept out of `domain` because it resolves platform string resources.
 */
internal fun captureFailureMessage(reason: CaptureFailure): Int = when (reason) {
    CaptureFailure.NO_CONSENT -> R.string.capture_failure_no_consent
    CaptureFailure.BLACK_FRAME -> R.string.capture_failure_black_frame
    CaptureFailure.NO_TEXT -> R.string.capture_failure_no_text
    CaptureFailure.OCR_TIMEOUT -> R.string.capture_failure_ocr_timeout
    CaptureFailure.SERVICE_DEAD -> R.string.capture_failure_service_dead
    CaptureFailure.NO_REMOTE_ENGINE -> R.string.capture_failure_no_remote_engine
    CaptureFailure.UNKNOWN -> R.string.capture_failure_unknown
}

class MainActivity : ComponentActivity() {

    private var incoming by mutableStateOf<SharedText.Incoming?>(null)

    /** Last payload handed to the ViewModel, so repeat deliveries are logged as such. */
    private var lastDelivered: String? = null

    /**
     * The last result handed back by the translucent capture chain, for the composition to apply.
     */
    private var captureDelivery by mutableStateOf<CaptureDelivery?>(null)

    /**
     * Hands the capture to the translucent chain, then steps out of its way.
     *
     * The chain (frame -> consent -> capture) has to run over the app the user was reading, never
     * over FlowAI: it is started while we are still in the foreground — starting activities from
     * the background is blocked — and [moveTaskToBack] then parks our opaque UI underneath the
     * chain's own translucent task, so the picker frames the chat. The result arrives later as an
     * intent extra ([consumeCaptureResult]), which also brings us forward again with the preview.
     */
    private fun requestScreenCapture() {
        runCatching { startActivity(InstantCaptureActivity.intent(this)) }
            .onFailure { Log.w(TAG, "could not start the capture chain", it) }
        moveTaskToBack(true)
    }

    /** Reads a capture-chain result out of [intent] and drops it, so a re-delivery cannot replay. */
    private fun consumeCaptureResult(intent: Intent?) {
        // Both capture entry points stage direct-image frames under the same shared extra.
        val imagePath = intent?.getStringExtra(CaptureDispatch.EXTRA_CAPTURED_IMAGE)
        val text = intent?.getStringExtra(InstantCaptureActivity.EXTRA_CAPTURED_TEXT)
        val failure = intent?.getStringExtra(InstantCaptureActivity.EXTRA_FAILURE)
        if (imagePath == null && text == null && failure == null) return
        intent.removeExtra(CaptureDispatch.EXTRA_CAPTURED_IMAGE)
        intent.removeExtra(InstantCaptureActivity.EXTRA_CAPTURED_TEXT)
        intent.removeExtra(InstantCaptureActivity.EXTRA_FAILURE)
        Log.i(
            TAG,
            "capture chain returned: chars=${text?.length ?: 0} image=$imagePath failure=$failure"
        )
        captureDelivery = CaptureDelivery(text, failure, imagePath)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Discard old-version bundles that may contain raw conversations/reply drafts.
        // ViewModel still survives configuration changes through non-config retention.
        super.onCreate(null)
        consumeSharedText(intent)
        consumeCaptureResult(intent)
        handleAssistantIntents(intent)
        // The external entry points outlive any single ViewModel, so they get a
        // process-surviving "already consumed" store. Without it, an intent re-delivered after
        // process death would silently re-import chat text the user had already ended the
        // session on.
        val factory = viewModelFactory { initializer {
            // One engine per configuration: the API engine caches the whole analysis from its first
            // call, so replacing it on every call would discard that result and fall back to local.
            val (engine, signature) =
                com.flowai.communication.ai.EngineSettingsStore.engineFactory(applicationContext)
            FlowViewModel(
                consumedShares = PrefsConsumedShareStore(applicationContext),
                engineFactory = engine,
                engineSignature = signature,
                replyStyles = com.flowai.communication.ai.ReplyStyleStore(applicationContext)
            )
        } }
        setContent {
            // Text fields and LazyColumn children can save state internally too.
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                FlowTheme {
                    FlowApp(
                        incoming = incoming,
                        captureDelivery = captureDelivery,
                        onRequestCapture = ::requestScreenCapture,
                        factory = factory
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Now that the app is almost always already in its task, a return from a panel capture
        // arrives here rather than in onCreate — so both paths must handle it.
        handleAssistantIntents(intent)
        consumeSharedText(intent)
        consumeCaptureResult(intent)
    }

    /**
     * Accepts text from either system entry point: the share sheet (`ACTION_SEND`) or the
     * text-selection toolbar (`ACTION_PROCESS_TEXT`). The selection toolbar matters because chat
     * apps often do not expose a share action for a chosen message.
     */
    /**
     * Handles the intents that belong to the assistant panel.
     *
     * Called from both [onCreate] and [onNewIntent]: MainActivity is `singleTask` and usually
     * already in its task, so a panel-capture return arrives as a new intent.
     */
    private fun handleAssistantIntents(incoming: Intent?) {
        if (incoming == null) return
        // Lets the assistant panel be opened directly (adb: --ez show_assistant true). Useful on
        // devices where injected touches cannot reach an overlay window, so the bubble itself
        // cannot be driven from a test harness.
        if (incoming.getBooleanExtra(EXTRA_SHOW_ASSISTANT, false)) {
            FloatingAssistantService.openPanel(applicationContext)
            incoming.removeExtra(EXTRA_SHOW_ASSISTANT)
        }
        // A capture started from the panel returns here; hand it back so the recognised text lands
        // in the panel's input box.
        if (incoming.getBooleanExtra(CaptureForPanelActivity.EXTRA_FROM_PANEL, false)) {
            val text = incoming.getStringExtra(CaptureForPanelActivity.EXTRA_CAPTURED_TEXT)
            val failure = incoming.getStringExtra(CaptureForPanelActivity.EXTRA_FAILURE)
            Log.i(TAG, "panel capture returned: chars=${text?.length ?: 0} failure=$failure")
            FloatingAssistantService.deliverCapture(text, failure)
            // Drop the extras so a task re-delivery does not replay the result.
            incoming.removeExtra(CaptureForPanelActivity.EXTRA_CAPTURED_TEXT)
            incoming.removeExtra(CaptureForPanelActivity.EXTRA_FAILURE)
            incoming.removeExtra(CaptureForPanelActivity.EXTRA_FROM_PANEL)
        }
    }

    private fun consumeSharedText(intent: Intent?) {
        val action = intent?.action
        val mime = intent?.type
        // Some senders put the payload only in ClipData and leave the extras null.
        val clipText = intent?.clipData
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
        // SEND_MULTIPLE carries a collection instead of a single EXTRA_TEXT. The extra's concrete
        // type varies by sender, so read the raw value and normalise it.
        @Suppress("DEPRECATION")
        val textItems = SharedText.normalizeItems(intent?.extras?.get(Intent.EXTRA_TEXT))
        val resolved = SharedText.resolve(
            action = action,
            mimeType = mime,
            text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT),
            html = intent?.getCharSequenceExtra(Intent.EXTRA_HTML_TEXT),
            processed = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
            clipText = clipText,
            textItems = textItems
        )
        if (resolved == null) {
            // Not one of our entry points at all (e.g. the launcher intent) — stay quiet.
            if (action != null && action != Intent.ACTION_MAIN) {
                val rawText = intent.extras?.get(Intent.EXTRA_TEXT)
                Log.i(
                    TAG,
                    "ignored intent: action=$action type=$mime " +
                        "textClass=${rawText?.javaClass?.simpleName} " +
                        "items=${textItems.size} extras=${intent.extras?.keySet()} clip=${intent.clipData?.itemCount}"
                )
            }
            return
        }
        if (lastDelivered == resolved.text) {
            // Rotation or a task re-delivery; the ViewModel would reject it anyway.
            Log.i(TAG, "repeat ${resolved.entry}: chars=${resolved.text.length} (not re-imported)")
        } else {
            Log.i(TAG, "received ${resolved.entry}: type=$mime chars=${resolved.text.length}")
            lastDelivered = resolved.text
        }
        incoming = resolved
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // This MVP intentionally supports only in-process session continuity.
        outState.clear()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        // Framework view state must not rehydrate legacy text independently of onCreate.
        super.onRestoreInstanceState(Bundle())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FlowApp(
    incoming: SharedText.Incoming? = null,
    captureDelivery: CaptureDelivery? = null,
    onRequestCapture: () -> Unit = {},
    factory: ViewModelProvider.Factory? = null,
    vm: FlowViewModel = if (factory != null) viewModel(factory = factory) else viewModel()
) {
    val context = LocalContext.current
    // A finished capture chain hands its result over here: the preview (or the failure notice)
    // opens just as the chain's own translucent task leaves the screen. A staged frame takes the
    // direct-image route instead: decode, delete the cache file at once, and analyse the picture.
    LaunchedEffect(captureDelivery) {
        val delivery = captureDelivery ?: return@LaunchedEffect
        val imagePath = delivery.imagePath
        if (imagePath != null) {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(imagePath) }.getOrNull().also {
                    runCatching { File(imagePath).delete() }
                }
            }
            if (bitmap != null) vm.analyzeScreenshot(bitmap)
            else vm.reportCaptureUnavailable(context.getString(R.string.capture_failure_unknown))
            return@LaunchedEffect
        }
        if (delivery.text != null) vm.showOcrPreview(delivery.text)
        else vm.reportCaptureUnavailable(delivery.failure.orEmpty())
    }
    LaunchedEffect(incoming) {
        val payload = incoming ?: return@LaunchedEffect
        val source = when (payload.entry) {
            SharedText.Entry.SHARE -> SourceType.SHARE
            SharedText.Entry.PROCESS_TEXT -> SourceType.PROCESS_TEXT
        }
        vm.consumeShare(payload.text, source)
    }
    BackHandler(enabled = vm.page != Page.HOME) { vm.back() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("FlowAI") }, navigationIcon = {
            if (vm.page != Page.HOME) TextButton(onClick = vm::back) {
                Text(when (vm.page) {
                    // Both hang off the analysis: leaving either returns there, session intact.
                    Page.ACTION, Page.CHAT -> "返回分析"
                    // No session exists on the preview yet — it starts at confirmation — so there
                    // is nothing to "end"; the button just abandons the draft.
                    Page.OCR_PREVIEW -> "取消"
                    // Side trips off the home page. They hold no session of their own and the way
                    // out is always back the way you came, so "结束返回" would promise an ending
                    // that does not happen on these three.
                    Page.SETTINGS, Page.SKINS, Page.HELP -> "返回首页"
                    else -> "结束返回"
                })
            }
        }, actions = { EngineBadge() })
    }, bottomBar = {
        // The preview holds no session yet, so the "end and clear" bar would promise something
        // that does not exist; its own bottom bar carries the recapture/confirm actions. The chat
        // brings its own input row, and stacking two bottom bars would push it off the keyboard.
        if (vm.page != Page.HOME && vm.page != Page.OCR_PREVIEW && vm.page != Page.CHAT) Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
                OutlinedButton(onClick = vm::endSession, modifier = Modifier.fillMaxWidth()) {
                    Text("结束并清除本次内容")
                }
                Text("返回首页也会清除。已复制的内容仍在剪贴板。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }) { padding ->
        // The whole Scaffold lifts above the keyboard. Padding only the scrolling content left this
        // bottom bar sitting on top of the IME, so the screen's own action stayed hidden behind it.
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when(vm.page) {
                Page.HOME -> HomeScreen(
                    open = vm::openInput,
                    clearedNotice = vm.clearedNotice,
                    captureNotice = vm.captureNotice,
                    onRequestCapture = onRequestCapture,
                    onOpenSettings = vm::openSettings,
                    onOpenSkins = vm::openSkins,
                    onOpenHelp = vm::openHelp
                )
                Page.INPUT -> InputScreen(
                    vm.input, vm.error, vm::edit, vm::analyze, vm.sourceType,
                    supersededNotice = vm.supersededNotice,
                    clearedNotice = vm.clearedNotice,
                    replyStyle = vm.replyStyle,
                    onSelectReplyStyle = vm::selectReplyStyle
                )
                Page.OCR_PREVIEW -> OcrPreviewScreen(
                    draft = vm.ocrDraft,
                    onEdit = vm::editOcrDraft,
                    onSubmit = vm::submitOcrDraft,
                    onRecapture = onRequestCapture
                )
                Page.ANALYSIS -> vm.analysis?.let { AnalysisScreen(it, vm::choose, vm::openChat) }
                Page.CHAT -> ChatScreen(vm.chat, vm.chatBusy, vm::sendChat)
                Page.ACTION -> vm.output?.let { output -> vm.selected?.let { action ->
                    ActionScreen(action, output, vm::editReply)
                } }
                Page.SETTINGS -> com.flowai.communication.ui.settings.EngineSettingsScreen(vm::back)
                Page.SKINS -> com.flowai.communication.ui.skins.PetSkinScreen(vm::back)
                Page.HELP -> HelpScreen()
            }
            // Direct image analysis is a network call with nothing on screen to show for it —
            // without this the home page would just sit there until the model answers.
            if (vm.busy && vm.page == Page.HOME) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在识别截屏文字并分析…")
                }
            }
        }
    }
}
