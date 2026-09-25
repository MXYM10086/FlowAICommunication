package com.flowai.communication.system

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The silent screenshot channel: one display capture per request, no consent dialog.
 *
 * MediaProjection demands consent for every capture session — mandatory on Android 14+ — which
 * puts a system dialog between framing a region and previewing its text. An accessibility
 * service's [takeScreenshot] needs no per-capture consent, only a one-time enablement in system
 * settings, so while this service runs the capture flow is exactly: frame, confirm, preview.
 *
 * The service observes nothing: window content and gestures are off in its configuration and
 * events are dropped on arrival. It exists solely to be a [takeScreenshot] caller, and every call
 * yields one frame that is recognised and released at once, keeping the Just-in-Time promise.
 */
class CaptureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "capture accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        if (instance === this) instance = null
    }

    companion object {
        private const val TAG = "FlowAI"

        @Volatile
        private var instance: CaptureAccessibilityService? = null

        /** True while the service is enabled and connected, i.e. silent capture can run. */
        val isRunning: Boolean get() = instance != null

        private val SCREENSHOT_EXECUTOR = Executors.newSingleThreadExecutor { r ->
            Thread(r, "FlowAI-a11y-shot")
        }

        /**
         * One full-display screenshot as a soft ARGB_8888 bitmap, or null when silent capture is
         * unavailable (service off, API below 30, or the system refused this capture).
         */
        suspend fun screenshot(): Bitmap? {
            val service = instance ?: run {
                Log.w(TAG, "silent screenshot skipped: service not connected")
                return null
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return service.captureOnce()
        }

        /**
         * The API-30 call itself, isolated behind the version guard in [screenshot].
         *
         * The platform hands over a hardware buffer; OCR needs readable pixels, so the frame is
         * copied out and both the hardware bitmap and the source buffer are released here — the
         * caller owns exactly one soft bitmap.
         */
        @RequiresApi(Build.VERSION_CODES.R)
        private suspend fun AccessibilityService.captureOnce(): Bitmap? =
            suspendCancellableCoroutine { cont ->
                runCatching {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        SCREENSHOT_EXECUTOR,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(result: ScreenshotResult) {
                                val soft = runCatching {
                                    val hardware = Bitmap.wrapHardwareBuffer(
                                        result.hardwareBuffer, result.colorSpace
                                    )
                                    val copied = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                                    hardware?.recycle()
                                    copied
                                }.onFailure { Log.w(TAG, "screenshot copy failed", it) }.getOrNull()
                                runCatching { result.hardwareBuffer.close() }
                                cont.resume(soft)
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.w(TAG, "accessibility screenshot refused: code=$errorCode")
                                cont.resume(null)
                            }
                        }
                    )
                }.onFailure {
                    // Never swallow this silently: a throw here surfaces as SERVICE_DEAD upstream,
                    // and without this line the cause is invisible in logcat.
                    Log.w(TAG, "takeScreenshot threw", it)
                    cont.resume(null)
                }
            }
    }
}
