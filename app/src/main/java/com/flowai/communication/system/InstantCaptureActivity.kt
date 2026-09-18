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
 * Capture launched straight from the floating bubble.
 *
 * Why this exists: a bubble that only re-opens the app is decoration — the launcher icon already
 * does that. What makes the bubble worth having is skipping the trip through the home screen:
 *
 *   before: bubble -> app home -> tap 截屏识别 -> frame -> consent -> recognise -> analyse
 *   now:    bubble -> frame -> consent -> analyse
 *
 * The region picker is translucent, so the app the user was looking at stays visible underneath
 * while they frame it. That is what lets the user pick the conversation they were just reading
 * instead of leaving it first.
 *
 * Finishes immediately and hands the recognised text to [MainActivity], so the analysis UI is
 * reached without the user pressing anything else.
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
        askForConsent()
    }

    private val projectionConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            Log.i(TAG, "instant capture consent denied")
            finishWith(null, "已取消截屏授权")
            return@registerForActivityResult
        }
        val started = ScreenCaptureService.start(
            applicationContext, result.resultCode, data, pendingRegion
        )
        if (!started) {
            finishWith(null, "无法启动截屏服务")
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
            val text = if (active) {
                withContext(Dispatchers.IO) {
                    ScreenCaptureService.captureText(ScreenCaptureService.requestedRegion())
                }
            } else null
            ScreenCaptureService.stop(applicationContext)
            Log.i(TAG, "instant capture: active=$active chars=${text?.length ?: 0}")
            finishWith(text, if (active) null else "没有拿到截屏权限或截屏服务未启动")
        }
    }

    /** Hands the result to the analysis UI, then leaves the screen. */
    private fun finishWith(text: String?, failure: String?) {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        if (!text.isNullOrBlank()) {
            intent.putExtra(EXTRA_CAPTURED_TEXT, text)
        } else if (failure != null) {
            intent.putExtra(EXTRA_FAILURE, failure)
        } else {
            intent.putExtra(EXTRA_FAILURE, "这次截屏没有识别到文字，请让聊天内容完整显示后重试")
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
