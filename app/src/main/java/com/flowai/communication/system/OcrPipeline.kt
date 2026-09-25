package com.flowai.communication.system

import android.graphics.Bitmap
import android.util.Log
import com.flowai.communication.domain.CaptureFailure
import com.flowai.communication.domain.CaptureResult
import java.util.concurrent.TimeoutException

/**
 * Turns one captured frame into recognised text — the local engine's reading of a screenshot.
 *
 * A configured remote engine reads the frame image itself and never comes through here; the
 * on-device engine cannot look at pictures, so both capture backends fall back to this pipeline.
 * It shares every reading rule across the MediaProjection and silent accessibility paths: a black
 * frame is a forbidden capture, tinted bubbles are normalised to ink-on-paper greyscale before
 * OCR — with a binarised second pass when the first read comes back thin — and line alignment
 * restores the speaker labels the parser needs.
 *
 * The recogniser is created per call and closed right after: every backend recognises exactly one
 * frame per user action, so a cached engine would only outlive the session it was opened for.
 */
internal object OcrPipeline {
    private const val TAG = "FlowAI"

    /** A frame with less non-black content than this is a forbidden/blank capture, not a chat. */
    private const val BLACK_FRAME_RATIO = 0.01f

    /** Luma at or below this counts as black; the same yardstick the screenshotters sample with. */
    private const val BLACK_PIXEL_THRESHOLD = 8

    /** A first pass reading fewer plausible lines than this retries on the binarised frame. */
    private const val MIN_PLAUSIBLE_LINES = 2

    /**
     * Recognises [bitmap] without taking ownership of it; the caller recycles the frame.
     *
     * Failures are classified rather than collapsed into null: a black frame (FLAG_SECURE) needs
     * different user guidance from an empty read or a recogniser that timed out.
     */
    fun recognize(bitmap: Bitmap): CaptureResult {
        if (nonBlackRatio(bitmap) < BLACK_FRAME_RATIO) {
            Log.i(TAG, "frame is almost fully black; treating as forbidden capture")
            return CaptureResult.Failure(CaptureFailure.BLACK_FRAME)
        }
        var prepared: Bitmap? = null
        return try {
            // The normalised greyscale frame is the primary read: it keeps both bubble polarities
            // legible. The prepared frame is ours to release, the raw shot always was.
            var frame = BitmapPreprocessor.normalize(bitmap).also { prepared = it }
            val outcome = runCatching { recognizeLines(frame) }
            if (outcome.isFailure) {
                Log.w(TAG, "ocr failed", outcome.exceptionOrNull())
                return if (outcome.exceptionOrNull() is TimeoutException) {
                    CaptureResult.Failure(CaptureFailure.OCR_TIMEOUT)
                } else {
                    CaptureResult.Failure(CaptureFailure.UNKNOWN)
                }
            }
            var lines = outcome.getOrNull().orEmpty()
            // A read this thin usually means the preparation fought the frame — white text on
            // tinted bubbles, an inverted theme — so give the binarised pass a chance and keep
            // whichever read survives the plausibility filter fuller.
            if (lines.count(::isPlausible) < MIN_PLAUSIBLE_LINES) {
                val binarised = BitmapPreprocessor.preprocess(bitmap)
                val fallback = runCatching { recognizeLines(binarised) }
                val alt = fallback.getOrNull().orEmpty()
                if (fallback.isSuccess && alt.count(::isPlausible) > lines.count(::isPlausible)) {
                    if (prepared !== bitmap) prepared?.recycle()
                    frame = binarised
                    prepared = binarised
                    lines = alt
                } else if (binarised !== bitmap) {
                    binarised.recycle()
                }
            }
            Log.i(TAG, "ocr lines=${lines.size} plausible=${lines.count(::isPlausible)}")
            val text = OcrTextAssembler.assembleWithSpeakers(lines, frame.width)
            if (lines.isEmpty() || text.isBlank()) {
                CaptureResult.Failure(CaptureFailure.NO_TEXT)
            } else {
                CaptureResult.Success(text)
            }
        } finally {
            if (prepared !== bitmap) prepared?.recycle()
        }
    }

    /** One frame through a freshly created recogniser; speaker labels are inferred later. */
    private fun recognizeLines(frame: Bitmap): List<OcrLine> {
        val engine = MlKitOcrEngine()
        return try {
            engine.recognize(frame)
        } finally {
            engine.close()
        }
    }

    private fun isPlausible(line: OcrLine): Boolean =
        OcrTextAssembler.isPlausibleDialogueLine(line)

    /**
     * Share of pixels above [BLACK_PIXEL_THRESHOLD], read in one bulk [Bitmap.getPixels].
     *
     * A chat screen is far from empty; a frame under 1% lit is a secure or unrendered window, not
     * a conversation that happens to be dark.
     */
    private fun nonBlackRatio(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0f
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var nonBlack = 0
        for (p in pixels) {
            val lum = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
            if (lum > BLACK_PIXEL_THRESHOLD) nonBlack++
        }
        return nonBlack.toFloat() / pixels.size
    }
}
