package com.flowai.communication.system

import android.graphics.Bitmap

/**
 * One recognised line of text, positioned in the captured image's coordinate space.
 *
 * Region cropping happens before OCR, so a caller can map these back onto the region it asked for.
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * On-device text recognition.
 *
 * Region selection is the caller's job: the caller crops to the conversation area and passes only
 * that bitmap, so unrelated screen content never reaches the recogniser.
 *
 * Implementations own native recognisers and must be closed.
 */
interface OcrEngine {
    /** Recognises [bitmap], returning lines in reading order. Empty when nothing was found. */
    fun recognize(bitmap: Bitmap): List<OcrLine>

    /** Releases the underlying recogniser. Safe to call twice. */
    fun close()
}

/**
 * Turns recognised lines into the plain-text shape the rest of the pipeline already understands.
 *
 * The dialogue parser's convention is one message per line, so lines are simply joined with
 * newlines. Joining with anything else would break speaker detection downstream.
 *
 * Free of Android APIs so it is unit-testable.
 */
object OcrTextAssembler {

    /**
     * Sorts lines top-to-bottom (then left-to-right for lines sharing a row) and joins them.
     *
     * OCR does not guarantee reading order, and a chat transcript is meaningless out of order.
     */
    fun assemble(lines: List<OcrLine>): String =
        lines
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .joinToString("\n") { it.text.trim() }

    /**
     * Drops lines that are almost certainly chrome rather than conversation: single stray
     * characters and status-bar residue add noise without carrying meaning.
     */
    fun isPlausibleDialogueLine(line: OcrLine): Boolean = line.text.trim().length >= MIN_LINE_CHARS

    private const val MIN_LINE_CHARS = 2

    /** Convenience: assemble only plausible lines. */
    fun assembleDialogue(lines: List<OcrLine>): String = assemble(lines.filter(::isPlausibleDialogueLine))
}
