package com.flowai.communication.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.flowai.communication.MainActivity
import com.flowai.communication.domain.CaptureRegion
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
        askForConsent()
    }

    private val projectionConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            Log.i(TAG, "panel capture consent denied")
            onFinished(null, "已取消截屏授权")
            return@registerForActivityResult
        }
        if (!ScreenCaptureService.start(applicationContext, result.resultCode, data, pendingRegion)) {
            onFinished(null, "无法启动截屏服务")
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

    private fun runCapture() {
        lifecycleScope.launch {
            var waited = 0L
            while (!ScreenCaptureService.isActive && waited < READY_TIMEOUT_MS) {
                delay(READY_INTERVAL_MS)
                waited += READY_INTERVAL_MS
            }
            delay(SETTLE_MS)
            val active = ScreenCaptureService.isActive
            val text = if (active) {
                withContext(Dispatchers.IO) {
                    ScreenCaptureService.captureText(ScreenCaptureService.requestedRegion())
                }
            } else null
            ScreenCaptureService.stop(applicationContext)
            Log.i(TAG, "panel capture: active=$active chars=${text?.length ?: 0}")
            onFinished(
                text,
                when {
                    !active -> "没有拿到截屏权限或截屏服务未启动"
                    else -> null
                }
            )
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
            else -> failure ?: "这次截屏没有识别到文字，请让聊天内容完整显示后重试"
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
