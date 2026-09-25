package com.flowai.communication.system

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.flowai.communication.domain.CaptureFailure
import com.flowai.communication.domain.CaptureRegion
import com.flowai.communication.domain.FrameResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The consent-free capture path built on [CaptureAccessibilityService].
 *
 * While the accessibility service is connected a confirmed region is screenshotted directly, so
 * nothing stands between framing and the preview. Without it the callers fall back to the
 * MediaProjection chain, whose per-session consent dialog Android 14+ makes unavoidable.
 */
object SilentCapture {
    private const val TAG = "FlowAI"

    /** Lets the picker's exit animation finish, so its scrim is not part of the frame. */
    private const val SETTLE_MS = 350L

    /** True when a capture can run without asking the user for consent again. */
    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            CaptureAccessibilityService.isRunning

    /**
     * Screenshots the display once, crops to [region] (null = whole screen) and returns the frame.
     *
     * Reading the frame — image analysis on a remote engine, OCR on the local one — belongs to the
     * caller ([CaptureDispatch]). Runs on [Dispatchers.IO]: the capture work is blocking and the
     * callers launch from their lifecycle scope. Ownership of the bitmap moves to the caller.
     *
     * [CaptureFailure.SERVICE_DEAD] covers "the service went away mid-capture": callers already
     * checked [available], so a null frame here means it died in between.
     */
    suspend fun captureFrame(region: CaptureRegion?): FrameResult = withContext(Dispatchers.IO) {
        delay(SETTLE_MS)
        val full = CaptureAccessibilityService.screenshot()
            ?: return@withContext FrameResult.Failure(CaptureFailure.SERVICE_DEAD)
        val framed = crop(full, region)
        Log.i(TAG, "silent capture: ${framed.width}x${framed.height}")
        FrameResult.Frame(framed)
    }

    /**
     * Same coordinate space as the MediaProjection backend: physical display pixels, which is
     * what the region picker reports and what a full-display screenshot is measured in.
     * Ownership of the frame moves to the returned bitmap; [full] is recycled when cropped.
     */
    private fun crop(full: Bitmap, region: CaptureRegion?): Bitmap {
        val clamped = region?.takeIf { it.isValid() }?.clampTo(full.width, full.height)
            ?: return full
        val cropped = Bitmap.createBitmap(full, clamped.left, clamped.top, clamped.width, clamped.height)
        full.recycle()
        return cropped
    }
}
