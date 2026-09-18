package com.flowai.communication.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.flowai.communication.MainActivity
import com.flowai.communication.domain.SharedText

/**
 * Entry point for the text-selection toolbar ("划词").
 *
 * Picking FlowAI from the selection toolbar should behave like an assistant, not like launching an
 * app: the analysis panel comes up over the app the user is already reading, with the selected text
 * already in place. So this activity holds no UI of its own — it forwards the selection to the
 * floating panel and finishes immediately, leaving the user where they were.
 *
 * When the overlay permission is missing there is no panel to show, and it falls back to the full
 * app so the selection is not silently dropped.
 */
class SelectionEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected = selectedTextFrom(intent)
        if (selected.isNullOrBlank()) {
            Log.i(TAG, "selection entry: no text, falling back")
            openFullApp()
            return
        }

        // PROCESS_TEXT callers may read a result back; nothing to return, but the contract expects
        // a result so the caller can close its toolbar.
        setResult(RESULT_CANCELED)

        if (FloatingAssistantService.showPanelWithText(applicationContext, selected)) {
            Log.i(TAG, "selection entry: handed ${selected.length} chars to the panel")
            finish()
            return
        }

        Log.i(TAG, "selection entry: no overlay permission, opening the app")
        openFullApp()
    }

    /**
     * Reads the selection.
     *
     * ACTION_PROCESS_TEXT carries plain text in `EXTRA_PROCESS_TEXT`; `EXTRA_PROCESS_TEXT_READONLY`
     * says whether the caller accepts a replacement, which this entry point never returns.
     */
    private fun selectedTextFrom(incoming: Intent?): String? {
        if (incoming == null) return null
        val resolved = SharedText.resolve(
            action = incoming.action,
            mimeType = incoming.type,
            text = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT),
            html = incoming.getCharSequenceExtra(Intent.EXTRA_HTML_TEXT),
            processed = incoming.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
            clipText = null,
            textItems = emptyList()
        )
        return resolved?.text
    }

    private fun openFullApp() {
        val forwarded = Intent(this, MainActivity::class.java).apply {
            action = intent?.action
            type = intent?.type
            putExtras(intent ?: Intent())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(forwarded) }
            .onFailure { Log.w(TAG, "could not open full app", it) }
        finish()
    }

    private companion object {
        const val TAG = "FlowAI"
    }
}
