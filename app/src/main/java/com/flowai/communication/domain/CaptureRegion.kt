package com.flowai.communication.domain

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A rectangle of the screen to capture, in absolute screen pixels.
 *
 * A null region means the whole screen. Capture is deliberately region-first: the product only
 * needs the conversation area, and scanning less of the screen means less unrelated content is
 * ever read — which is the point of Just-in-Time Context.
 *
 * Parcelable so it can be handed to the capture service with the consent result.
 */
@Parcelize
data class CaptureRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) : Parcelable {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** Too small to contain a conversation; treated as "no region" by callers. */
    fun isValid(): Boolean = width > MIN_SIZE && height > MIN_SIZE

    /** Clamps the region into a screen of [screenWidth] x [screenHeight]. */
    fun clampTo(screenWidth: Int, screenHeight: Int): CaptureRegion = CaptureRegion(
        left = left.coerceIn(0, screenWidth),
        top = top.coerceIn(0, screenHeight),
        right = right.coerceIn(0, screenWidth),
        bottom = bottom.coerceIn(0, screenHeight)
    )

    companion object {
        /** Kept small so a slightly-off drag still yields something usable. */
        const val MIN_SIZE = 8

        fun fullScreen(width: Int, height: Int): CaptureRegion =
            CaptureRegion(0, 0, width, height)
    }
}
