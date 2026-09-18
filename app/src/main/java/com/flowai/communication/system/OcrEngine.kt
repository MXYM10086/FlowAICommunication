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

    /**
     * Assembles a chat-shaped transcript, inferring speakers from where the text sits.
     *
     * A bare transcription cannot be analysed: the dialogue parser needs `speaker: text`, and
     * without labels every line becomes an unknown speaker, which collapses the analysis into the
     * generic fallback. Chat apps place the two parties on opposite sides, so horizontal alignment
     * is the signal — right-aligned lines are the user's own messages, left-aligned lines belong to
     * the other party.
     *
     * Lines that span most of the width carry no positional signal and are left unattributed rather
     * than guessed at.
     *
     * Free of Android APIs so it is unit-testable.
     */
    fun assembleWithSpeakers(lines: List<OcrLine>, imageWidth: Int): String {
        if (imageWidth <= 0) return assembleDialogue(lines)
        val usable = lines.filter(::isPlausibleDialogueLine)
        if (usable.isEmpty()) return ""

        val order = usable.sortedWith(compareBy({ it.top }, { it.left }))
        return order.joinToString("\n") { line ->
            when (sideOf(line, imageWidth)) {
                Side.MINE -> LABEL_MINE + line.text.trim()
                Side.THEIRS -> LABEL_THEIRS + line.text.trim()
                Side.UNCLEAR -> line.text.trim()
            }
        }
    }

    private enum class Side { MINE, THEIRS, UNCLEAR }

    /**
     * Which side of the conversation a line belongs to.
     *
     * Requires both the centre *and* the nearer edge to fall on the same side, so a wide line that
     * merely leans one way is treated as unclear instead of being mislabelled.
     */
    private fun sideOf(line: OcrLine, imageWidth: Int): Side {
        val centre = imageWidth / 2f
        val lineCentre = (line.left + line.right) / 2f
        val spansMost = (line.right - line.left) >= imageWidth * SPAN_RATIO
        if (spansMost) return Side.UNCLEAR
        return when {
            lineCentre > centre + centre * SIDE_MARGIN_RATIO -> Side.MINE
            lineCentre < centre - centre * SIDE_MARGIN_RATIO -> Side.THEIRS
            else -> Side.UNCLEAR
        }
    }

    const val LABEL_MINE = "我："
    const val LABEL_THEIRS = "对方："

    /** A line wider than this share of the image is treated as spanning the whole width. */
    private const val SPAN_RATIO = 0.82f

    /** How far past the centre a line must sit before its side is trusted. */
    private const val SIDE_MARGIN_RATIO = 0.06f
}
