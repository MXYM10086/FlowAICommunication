package com.flowai.communication.domain

/**
 * Why a screen capture did not produce an analysis.
 *
 * The UI guides the user differently per reason: a black frame means the app forbade capture and
 * sharing text is the way out, while a missing engine sends them to the settings screen.
 * Collapsing these into one generic error would send the user down the wrong path.
 *
 * Platform-free on purpose: this lives in `domain` so the capture service, the activities and any
 * future entry point all classify failures with the same vocabulary.
 */
enum class CaptureFailure {
    /** The user dismissed the MediaProjection consent dialog. */
    NO_CONSENT,

    /** Every sampled frame was (near-)black: a FLAG_SECURE window or nothing rendered at all. */
    BLACK_FRAME,

    /** The frame was captured fine but the recogniser found no text in it. */
    NO_TEXT,

    /** The recogniser did not come back in time. */
    OCR_TIMEOUT,

    /** The capture service was not running, or could not be started. */
    SERVICE_DEAD,

    /**
     * No remote engine is configured and consented, so there is nobody to read the screenshot.
     *
     * Capture analyses the *image* through the model — there is deliberately no on-device OCR
     * fallback — so an unconfigured install is told to set an engine up rather than being handed
     * a recognised-text flow the product no longer has.
     */
    NO_REMOTE_ENGINE,

    /** Anything that could not be attributed to one of the above; the safe fallback. */
    UNKNOWN
}

/**
 * Outcome of one capture attempt: recognised text, or the reason there is none.
 *
 * Replaces the old nullable-String contract, where "null" hid five very different situations
 * behind one undifferentiated failure.
 */
sealed class CaptureResult {
    data class Success(val text: String) : CaptureResult()
    data class Failure(val reason: CaptureFailure) : CaptureResult()
}
