package com.flowai.communication.domain

import android.graphics.Bitmap

/**
 * One framed screenshot, captured but not yet interpreted.
 *
 * Capture backends used to OCR inside themselves and hand back text; now what a backend produces
 * is a *frame*, and the caller decides how it is read — [com.flowai.communication.system.FrameAnalysis]
 * recognises the frame's text on device and analyses that text.
 */
sealed interface FrameResult {

    /**
     * The framed screen content. The receiver owns the bitmap and must recycle it once read —
     * a screenshot is the most sensitive artifact in the pipeline and must not outlive its use.
     */
    class Frame(val bitmap: Bitmap) : FrameResult

    /** The frame could not be taken at all; classified like the text path's failures. */
    data class Failure(val reason: CaptureFailure) : FrameResult
}
