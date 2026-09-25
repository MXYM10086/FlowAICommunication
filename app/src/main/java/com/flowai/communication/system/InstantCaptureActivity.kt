package com.flowai.communication.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.flowai.communication.MainActivity
import com.flowai.communication.R
import com.flowai.communication.captureFailureMessage
import com.flowai.communication.domain.CaptureFailure
import com.flowai.communication.domain.CaptureRegion
import com.flowai.communication.domain.CaptureResult
import com.flowai.communication.domain.FrameResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The translucent capture chain: frame -> consent -> capture, over whatever app is on screen.
 *
 * The in-app capture button starts this and immediately moves the main task to the background, so
 * the picker frames the chat the user was reading instead of FlowAI's own UI. The manifest keeps
 * this activity in a task of its own (empty taskAffinity): inside the main task its translucent
 * window would show the opaque MainActivity through it, and every capture would photograph FlowAI.
 *
 * Finishes immediately and hands the staged frame to [MainActivity]
 * ([CaptureDispatch.EXTRA_CAPTURED_IMAGE]), where the frame's text is recognised on device and
 * analysed — the picture itself never leaves the phone, and no preview step stands in between.
 */
class InstantCaptureActivity : ComponentActivity() {

    private var pendingRegion: CaptureRegion? = null

    private val regionPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        pendingRegion = if (result.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(RegionPickerActivity.EXTRA_REGION)
        } else {
            null // cancelled, or "whole screen" which also returns no region
        }
        Log.i(TAG, "instant capture region: ${pendingRegion ?: "full screen"}")
        when {
            // A share session pre-authorised when the floating window started: read at once.
            ScreenCaptureService.isActive -> runLiveCapture()
            SilentCapture.available -> runSilentCapture()
            else -> askForConsent()
        }
    }

    private val projectionConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            Log.i(TAG, "instant capture consent denied")
            finishWith(null, getString(R.string.capture_failure_no_consent))
            return@registerForActivityResult
        }
        val started = ScreenCaptureService.start(
            applicationContext, result.resultCode, data, pendingRegion
        )
        if (!started) {
            finishWith(null, getString(R.string.capture_failure_service_dead))
            return@registerForActivityResult
        }
        runCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No UI of its own: this activity only chains the picker and the consent dialog.
        if (savedInstanceState == null) {
            regionPicker.launch(RegionPickerActivity.intent(this))
        }
    }

    private fun askForConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionConsent.launch(manager.createScreenCaptureIntent())
    }

    /** Consent-free path: the accessibility service screenshots the framed region directly. */
    private fun runSilentCapture() {
        lifecycleScope.launch {
            Log.i(TAG, "instant capture: silent path")
            handleFrame(SilentCapture.captureFrame(pendingRegion))
        }
    }

    /**
     * The pre-authorised share session is already mirroring the display, so the framed region is
     * read off the current screen immediately — no dialog, no service start-up, no stale frame.
     */
    private fun runLiveCapture() {
        lifecycleScope.launch {
            Log.i(TAG, "instant capture: live share session")
            delay(SETTLE_MS)
            val result = withContext(Dispatchers.IO) {
                ScreenCaptureService.captureFrame(pendingRegion)
            }
            // Kept alive while the floating window runs: the next capture reuses the session.
            if (!FloatingAssistantService.isRunning) ScreenCaptureService.stop(applicationContext)
            handleFrame(result)
        }
    }

    /**
     * The frame goes on to be read as an image first, with on-device text recognition as the
     * fallback — [FrameAnalysis] decides which one serves it, in the full app. Every frame is
     * worth an analysis attempt, so nothing is turned away here.
     */
    private fun handleFrame(result: FrameResult) {
        lifecycleScope.launch {
            val frame = result as? FrameResult.Frame
            if (frame == null) {
                deliver(CaptureResult.Failure((result as FrameResult.Failure).reason))
                return@launch
            }
            deliverImage(frame.bitmap)
        }
    }

    private suspend fun deliverImage(bitmap: Bitmap) {
        val path = CaptureDispatch.toCacheFile(applicationContext, bitmap)
        if (path == null) {
            deliver(CaptureResult.Failure(CaptureFailure.UNKNOWN))
            return
        }
        Log.i(TAG, "instant capture: frame staged for direct image analysis")
        val intent = Intent(this@InstantCaptureActivity, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        intent.putExtra(CaptureDispatch.EXTRA_CAPTURED_IMAGE, path)
        startActivity(intent)
        finish()
    }

    private fun deliver(result: CaptureResult) {
        when (result) {
            is CaptureResult.Success -> {
                Log.i(TAG, "instant capture: chars=${result.text.length}")
                finishWith(result.text, null)
            }
            is CaptureResult.Failure -> {
                Log.i(TAG, "instant capture failed: ${result.reason}")
                finishWith(null, getString(captureFailureMessage(result.reason)))
            }
        }
    }

    /** Mirrors the in-app flow, but owned by this activity so it survives the dialogs. */
    private fun runCapture() {
        lifecycleScope.launch {
            var waited = 0L
            while (!ScreenCaptureService.isActive && waited < READY_TIMEOUT_MS) {
                delay(READY_INTERVAL_MS)
                waited += READY_INTERVAL_MS
            }
            delay(SETTLE_MS)
            val active = ScreenCaptureService.isActive
            val result = if (active) {
                withContext(Dispatchers.IO) {
                    ScreenCaptureService.captureFrame(ScreenCaptureService.requestedRegion())
                }
            } else {
                FrameResult.Failure(CaptureFailure.SERVICE_DEAD)
            }
            // Kept alive while the floating window runs: the next capture reuses the session.
            if (!FloatingAssistantService.isRunning) ScreenCaptureService.stop(applicationContext)
            handleFrame(result)
        }
    }

    /** Hands the result to the analysis UI, then leaves the screen. */
    private fun finishWith(text: String?, failure: String?) {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        if (!text.isNullOrBlank()) {
            intent.putExtra(EXTRA_CAPTURED_TEXT, text)
        } else {
            intent.putExtra(EXTRA_FAILURE, failure ?: getString(R.string.capture_failure_no_text))
        }
        startActivity(intent)
        finish()
    }

    companion object {
        private const val TAG = "FlowAI"

        const val EXTRA_CAPTURED_TEXT = "flowai.instant.text"
        const val EXTRA_FAILURE = "flowai.instant.failure"

        private const val READY_TIMEOUT_MS = 30_000L
        private const val READY_INTERVAL_MS = 100L
        private const val SETTLE_MS = 600L

        /** Entry point used by the floating bubble. */
        fun intent(context: Context): Intent =
            Intent(context, InstantCaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
