package com.flowai.communication.system

import android.graphics.Bitmap
import com.flowai.communication.domain.CaptureRegion

/**
 * A screenshot backend.
 *
 * Split out as an interface because the backends differ in consent cost, not in capability:
 * `MediaProjection` works everywhere but asks the user every session on Android 14+, while a
 * future Shizuku backend could avoid the prompt. Callers should not care which one is active.
 *
 * Implementations own real platform resources, so [release] must be called — the Just-in-Time
 * Context rule is that a capture never outlives the session that asked for it.
 */
interface Screenshotter {

    /** True when the backend has consent and its capture surface is ready. */
    val isReady: Boolean

    /**
     * Grabs one frame, cropped to [region] (null means the whole screen).
     *
     * Returns null when the backend is not ready or no frame is available; callers should treat
     * that as "nothing captured" rather than an error.
     */
    fun capture(region: CaptureRegion? = null): Bitmap?

    /** Releases the virtual display, reader and projection token. Safe to call twice. */
    fun release()
}
