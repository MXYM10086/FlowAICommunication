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
 * Runs a screen capture on behalf of the assistant panel.
 *
 * Kept as an Activity because MediaProjection consent can only be requested from one, and the
 * region picker needs a window of its own.
 *
 * Ordering matters: the panel must be hidden **before** the frame is taken, otherwise the overlay
 * itself would appear in the capture and be fed to OCR. It is restored by [onFinished] on the way
 * out.
 */
class CaptureForPanelActivity : ComponentActivity() {

    private var pendingRegion: CaptureRegion? = null

    private val regionPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        pendingRegion = if (result.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(RegionPickerActivity.EXTRA_REGION)
        } else {
            null // cancelled, or "whole screen"
        }
        Log.i(TAG, "panel capture region: ${pendingRegion ?: "full screen"}")
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
            Log.i(TAG, "panel capture consent denied")
            onFinished(null, getString(R.string.capture_failure_no_consent))
            return@registerForActivityResult
        }
        if (!ScreenCaptureService.start(applicationContext, result.resultCode, data, pendingRegion)) {
            onFinished(null, getString(R.string.capture_failure_service_dead))
            return@registerForActivityResult
        }
        runCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            regionPicker.launch(RegionPickerActivity.intent(this))
        }
    }

    private fun askForConsent() {
        getSystemService(MediaProjectionManager::class.java)
            .createScreenCaptureIntent()
            .let { projectionConsent.launch(it) }
    }

    /** Consent-free path: the accessibility service screenshots the framed region directly. */
    private fun runSilentCapture() {
        lifecycleScope.launch {
            Log.i(TAG, "panel capture: silent path")
            handleFrame(SilentCapture.captureFrame(pendingRegion))
        }
    }

    /**
     * The pre-authorised share session is already mirroring the display, so the framed region is
     * read off the current screen immediately — no dialog, no service start-up, no stale frame.
     */
    private fun runLiveCapture() {
        lifecycleScope.launch {
            Log.i(TAG, "panel capture: live share session")
            delay(SETTLE_MS)
            val result = withContext(Dispatchers.IO) {
                ScreenCaptureService.captureFrame(pendingRegion)
            }
            // The session belongs to the floating window and outlives this single read.
            handleFrame(result)
        }
    }

    /**
     * The framed picture is analysed *in place*: the staged frame goes to the floating panel, which
     * reads it (image first, on-device text as fallback) and shows the result over the chat app —
     * the user never leaves the conversation. Only when the floating service is gone does the frame
     * take the old road into the full app.
     */
    private fun handleFrame(result: FrameResult) {
        lifecycleScope.launch {
            val frame = result as? FrameResult.Frame
            if (frame == null) {
                handle(CaptureResult.Failure((result as FrameResult.Failure).reason))
                return@launch
            }
            val path = CaptureDispatch.toCacheFile(applicationContext, frame.bitmap)
            if (path != null && FloatingAssistantService.deliverCaptureImage(path)) {
                Log.i(TAG, "panel capture: frame handed to the panel for in-place analysis")
                finish()
                return@launch
            }
            if (path == null) {
                onFinished(null, getString(R.string.capture_failure_unknown))
                return@launch
            }
            Log.w(TAG, "panel capture: no live panel; routing the frame to the full app")
            routeImageToApp(path)
        }
    }

    /** Fallback road: the full app analyses the staged frame in its own flow. */
    private fun routeImageToApp(path: String) {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        intent.putExtra(CaptureDispatch.EXTRA_CAPTURED_IMAGE, path)
        startActivity(intent)
        finish()
    }

    private fun handle(result: CaptureResult) {
        when (result) {
            is CaptureResult.Success -> {
                Log.i(TAG, "panel capture: chars=${result.text.length}")
                onFinished(result.text, null)
            }
            is CaptureResult.Failure -> {
                Log.i(TAG, "panel capture failed: ${result.reason}")
                onFinished(null, getString(captureFailureMessage(result.reason)))
            }
        }
    }

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

    /**
     * Hands the recognised text straight back to the panel and leaves the screen alone.
     *
     * Previously this started MainActivity to deliver the result, which yanked the app to the
     * foreground — so the user lost sight of what they were analysing and the *next* capture
     * photographed FlowAI itself. Delivery does not need an Activity at all: the panel lives in
     * this same process.
     *
     * Only when the service is gone (killed during the consent dialogs, which the panel cannot
     * survive either) is MainActivity started, and then it exists solely to restart the service.
     */
    private fun onFinished(text: String?, failure: String?) {
        val message = when {
            !text.isNullOrBlank() -> null
            else -> failure ?: getString(R.string.capture_failure_no_text)
        }

        if (FloatingAssistantService.deliverCapture(text, message)) {
            Log.i(TAG, "delivered to live panel; not starting any activity")
            finish()
            return
        }

        // Fallback: the service is gone, so restart it behind MainActivity and let MainActivity
        // forward the result.
        Log.w(TAG, "no live service; falling back to MainActivity")
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        intent.putExtra(EXTRA_FROM_PANEL, true)
        text?.takeIf { it.isNotBlank() }?.let { intent.putExtra(EXTRA_CAPTURED_TEXT, it) }
        if (text.isNullOrBlank()) intent.putExtra(EXTRA_FAILURE, message)
        startActivity(intent)
        finish()
    }

    companion object {
        private const val TAG = "FlowAI"

        const val EXTRA_FROM_PANEL = "flowai.panel.capture"
        const val EXTRA_CAPTURED_TEXT = "flowai.panel.text"
        const val EXTRA_FAILURE = "flowai.panel.failure"

        private const val READY_TIMEOUT_MS = 30_000L
        private const val READY_INTERVAL_MS = 100L
        private const val SETTLE_MS = 600L

        fun intent(context: Context): Intent =
            Intent(context, CaptureForPanelActivity::class.java)
    }
}
