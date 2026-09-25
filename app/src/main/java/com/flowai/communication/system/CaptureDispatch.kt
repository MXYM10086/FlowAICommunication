package com.flowai.communication.system

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stages a captured frame for the reader that takes it on.
 *
 * Capture hands the *frame* on: it is staged as a PNG and [FrameAnalysis] recognises its text on
 * device before analysing it, so the picture itself never leaves the phone. A bitmap is far too
 * large for an intent, so the frame crosses activity boundaries as a PNG in our own cache dir; the
 * receiver decodes it and deletes the file at once.
 */
internal object CaptureDispatch {
    private const val TAG = "FlowAI"

    /**
     * Intent extra carrying the staged frame's file path.
     */
    const val EXTRA_CAPTURED_IMAGE = "flowai.capture.image_path"

    /**
     * PNG-encodes the frame into the cache dir for intent transport and consumes the bitmap.
     * Returns the file path, or null if staging failed.
     */
    suspend fun toCacheFile(context: Context, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        try {
            val file = File(context.cacheDir, "capture-frame-${System.currentTimeMillis()}.png")
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            Log.i(TAG, "capture frame staged: ${file.length() / 1024} KiB")
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "could not stage the capture frame", e)
            null
        } finally {
            bitmap.recycle()
        }
    }
}
