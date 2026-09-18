package com.flowai.communication

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.flowai.communication.data.model.SourceType
import com.flowai.communication.domain.CaptureRegion
import com.flowai.communication.domain.PrefsConsumedShareStore
import com.flowai.communication.domain.SharedText
import com.flowai.communication.system.CaptureForPanelActivity
import com.flowai.communication.system.FloatingAssistantService
import com.flowai.communication.system.RegionPickerActivity
import com.flowai.communication.system.ScreenCaptureService
import com.flowai.communication.ui.*
import com.flowai.communication.ui.home.*
import com.flowai.communication.ui.analysis.AnalysisScreen
import com.flowai.communication.ui.action.ActionScreen
import com.flowai.communication.ui.components.FlowTheme

/** Extra that opens the assistant panel directly; used for testing and from the home screen. */
private const val EXTRA_SHOW_ASSISTANT = "show_assistant"

/** adb logcat -s FlowAI */
private const val TAG = "FlowAI"

/**
 * Upper bound on waiting for the projection to come up after consent.
 *
 * Generous on purpose: the user reads a consent dialog first, and a timeout shorter than that
 * (5s was too short in practice) would abandon the capture before it ever started.
 */
private const val CAPTURE_READY_TIMEOUT_MS = 30_000L
private const val CAPTURE_READY_INTERVAL_MS = 100L

/** Extra settle time so the virtual display has produced at least one frame. */
private const val CAPTURE_SETTLE_MS = 600L

class MainActivity : ComponentActivity() {

    private var incoming by mutableStateOf<SharedText.Incoming?>(null)

    /** Last payload handed to the ViewModel, so repeat deliveries are logged as such. */
    private var lastDelivered: String? = null

    /** Set while the capture session is being established, so the UI can show progress. */
    private var captureInProgress by mutableStateOf(false)

    /** The live ViewModel, so the capture coroutine can deliver results. */
    private var activeVm: FlowViewModel? = null

    /** Region chosen in the picker, or null for the whole screen. */
    private var pendingRegion: CaptureRegion? = null

    /** Owns the capture coroutine; the Activity outlives the consent dialog. */
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Region picker. Returns the framed conversation area, which both improves OCR accuracy and
     * keeps unrelated screen content out of the pipeline.
     */
    private val regionPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        pendingRegion = if (result.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(RegionPickerActivity.EXTRA_REGION)
        } else {
            // Cancelled, or the user chose full screen (which also returns CANCELED + no region).
            null
        }
        val chosen = pendingRegion
        Log.i(TAG, if (chosen == null) "capture region: full screen" else "capture region: $chosen")
        askForCaptureConsent()
    }

    /** Asks for capture consent; the result callback then runs [runCapture]. */
    private fun requestScreenCapture() {
        if (captureInProgress) return
        captureInProgress = true
        regionPicker.launch(RegionPickerActivity.intent(this))
    }

    private fun askForCaptureConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionConsent.launch(manager.createScreenCaptureIntent())
    }

    /**
     * MediaProjection consent. Android 14 requires consent for EVERY capture session, so this
     * launcher is used per capture and its result is never cached or reused.
     */
    private val projectionConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            Log.i(TAG, "screen capture consent denied")
            activeVm?.reportCaptureUnavailable("已取消截屏授权")
            captureInProgress = false
            return@registerForActivityResult
        }
        val started = ScreenCaptureService.start(applicationContext, result.resultCode, data, pendingRegion)
        Log.i(TAG, "screen capture session start requested: started=$started")
        if (!started) {
            activeVm?.reportCaptureUnavailable("无法启动截屏服务")
            captureInProgress = false
        } else {
            runCapture()
        }
    }

    /**
     * Waits for the projection, captures once, and hands the text to the normal pipeline.
     *
     * Runs on [captureScope] rather than inside the composition: the consent dialog backgrounds the
     * host and a composition-scoped effect can be cancelled mid-wait.
     */
    private fun runCapture() {
        captureScope.launch {
            // Wait for the projection to come up. Without this the first frames do not exist yet
            // and the capture would look like a broken OCR rather than an early read.
            var waited = 0L
            while (!ScreenCaptureService.isActive && waited < CAPTURE_READY_TIMEOUT_MS) {
                delay(CAPTURE_READY_INTERVAL_MS)
                waited += CAPTURE_READY_INTERVAL_MS
            }
            // Even once active the virtual display needs a frame or two.
            delay(CAPTURE_SETTLE_MS)

            val active = ScreenCaptureService.isActive
            val text = if (active) {
                withContext(Dispatchers.IO) {
                    ScreenCaptureService.captureText(ScreenCaptureService.requestedRegion())
                }
            } else null
            ScreenCaptureService.stop(applicationContext)

            val vm = activeVm
            when {
                !active -> {
                    Log.i(TAG, "capture session never became active")
                    vm?.reportCaptureUnavailable("没有拿到截屏权限或截屏服务未启动")
                }
                text.isNullOrBlank() -> {
                    Log.i(TAG, "screen capture produced no text")
                    vm?.reportCaptureEmpty()
                }
                else -> {
                    Log.i(TAG, "screen capture recognised chars=${text.length}")
                    // allowSameText: re-reading the same screen is an explicit user action.
                    vm?.consumeShare(text, SourceType.SCREENSHOT, allowSameText = true)
                }
            }
            captureInProgress = false
        }
    }

    override fun onDestroy() {
        captureScope.cancel()
        super.onDestroy()
    }

    private companion object {
        // Constants shared with the composition are file-level (see top of this file).
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Discard old-version bundles that may contain raw conversations/reply drafts.
        // ViewModel still survives configuration changes through non-config retention.
        super.onCreate(null)
        consumeSharedText(intent)
        handleAssistantIntents(intent)
        // The external entry points outlive any single ViewModel, so they get a
        // process-surviving "already consumed" store. Without it, an intent re-delivered after
        // process death would silently re-import chat text the user had already ended the
        // session on.
        val factory = viewModelFactory { initializer { FlowViewModel(PrefsConsumedShareStore(applicationContext)) } }
        setContent {
            // Text fields and LazyColumn children can save state internally too.
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                FlowTheme {
                    FlowApp(
                        incoming = incoming,
                        captureInProgress = captureInProgress,
                        onRequestCapture = ::requestScreenCapture,
                        onCaptureFinished = { captureInProgress = false },
                        onViewModelReady = { activeVm = it },
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
    captureInProgress: Boolean = false,
    onRequestCapture: () -> Unit = {},
    onCaptureFinished: () -> Unit = {},
    onViewModelReady: (FlowViewModel) -> Unit = {},
    factory: ViewModelProvider.Factory? = null,
    vm: FlowViewModel = if (factory != null) viewModel(factory = factory) else viewModel()
) {
    val context = LocalContext.current
    // Hand the ViewModel to the Activity so the capture coroutine (owned by the Activity, not the
    // composition) can deliver its result. Driving capture from a LaunchedEffect is fragile here:
    // the consent dialog backgrounds the host, which can cancel a composition-scoped effect.
    LaunchedEffect(vm) { onViewModelReady(vm) }
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
                Text(if (vm.page == Page.ACTION) "返回分析" else "结束返回")
            }
        }, actions = { Text("MVP · MOCK  ", style = MaterialTheme.typography.labelMedium) })
    }, bottomBar = {
        if (vm.page != Page.HOME) Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
                OutlinedButton(onClick = vm::endSession, modifier = Modifier.fillMaxWidth()) {
                    Text("结束并清除本次内容")
                }
                Text("返回首页也会清除。已复制的内容仍在剪贴板。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when(vm.page) {
                Page.HOME -> HomeScreen(
                    open = vm::openInput,
                    clearedNotice = vm.clearedNotice,
                    captureNotice = vm.captureNotice,
                    captureInProgress = captureInProgress,
                    onRequestCapture = onRequestCapture
                )
                Page.INPUT -> InputScreen(
                    vm.input, vm.error, vm::edit, vm::analyze, vm.sourceType,
                    supersededNotice = vm.supersededNotice,
                    clearedNotice = vm.clearedNotice
                )
                Page.ANALYSIS -> vm.analysis?.let { AnalysisScreen(it, vm::choose) }
                Page.ACTION -> vm.output?.let { output -> vm.selected?.let { action ->
                    ActionScreen(action, output, vm::editReply)
                } }
            }
        }
    }
}
