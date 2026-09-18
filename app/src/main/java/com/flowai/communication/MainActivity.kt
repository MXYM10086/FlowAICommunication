package com.flowai.communication

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flowai.communication.data.model.SourceType
import com.flowai.communication.domain.PrefsConsumedShareStore
import com.flowai.communication.domain.SharedText
import com.flowai.communication.ui.*
import com.flowai.communication.ui.home.*
import com.flowai.communication.ui.analysis.AnalysisScreen
import com.flowai.communication.ui.action.ActionScreen
import com.flowai.communication.ui.components.FlowTheme

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<SharedText.Incoming?>(null)

    /** Last payload handed to the ViewModel, so repeat deliveries are logged as such. */
    private var lastDelivered: String? = null

    private companion object {
        /** adb logcat -s FlowAI */
        const val TAG = "FlowAI"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Discard old-version bundles that may contain raw conversations/reply drafts.
        // ViewModel still survives configuration changes through non-config retention.
        super.onCreate(null)
        consumeSharedText(intent)
        // The external entry points outlive any single ViewModel, so they get a
        // process-surviving "already consumed" store. Without it, an intent re-delivered after
        // process death would silently re-import chat text the user had already ended the
        // session on.
        val factory = viewModelFactory { initializer { FlowViewModel(PrefsConsumedShareStore(applicationContext)) } }
        setContent {
            // Text fields and LazyColumn children can save state internally too.
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                FlowTheme { FlowApp(incoming = incoming, factory = factory) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSharedText(intent)
    }

    /**
     * Accepts text from either system entry point: the share sheet (`ACTION_SEND`) or the
     * text-selection toolbar (`ACTION_PROCESS_TEXT`). The selection toolbar matters because chat
     * apps often do not expose a share action for a chosen message.
     */
    private fun consumeSharedText(intent: Intent?) {
        val action = intent?.action
        val mime = intent?.type
        // Some senders put the payload only in ClipData and leave the extras null.
        val clipText = intent?.clipData
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
        val resolved = SharedText.resolve(
            action = action,
            mimeType = mime,
            text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT),
            html = intent?.getCharSequenceExtra(Intent.EXTRA_HTML_TEXT),
            processed = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
            clipText = clipText
        )
        if (resolved == null) {
            // Not one of our entry points at all (e.g. the launcher intent) — stay quiet.
            if (action != null && action != Intent.ACTION_MAIN) {
                Log.i(TAG, "ignored intent: action=$action type=$mime extras=${intent.extras?.keySet()} clip=${intent.clipData?.itemCount}")
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
    factory: ViewModelProvider.Factory? = null,
    vm: FlowViewModel = if (factory != null) viewModel(factory = factory) else viewModel()
) {
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
                Page.HOME -> HomeScreen(vm::openInput, vm.clearedNotice)
                Page.INPUT -> InputScreen(vm.input, vm.error, vm::edit, vm::analyze, vm.sourceType)
                Page.ANALYSIS -> vm.analysis?.let { AnalysisScreen(it, vm::choose) }
                Page.ACTION -> vm.output?.let { output -> vm.selected?.let { action ->
                    ActionScreen(action, output, vm::editReply)
                } }
            }
        }
    }
}
