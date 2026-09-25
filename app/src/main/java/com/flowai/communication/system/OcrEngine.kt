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
    val bottom: Int,
    /**
     * Recogniser confidence in [0, 1]; defaults to 1 so engines and tests that carry no confidence
     * keep their previous behaviour.
     */
    val confidence: Float = 1f
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
 * Stateless on purpose: everything lives in the companion so callers keep the
 * `OcrTextAssembler.assemble(...)` shape, and none of it touches Android APIs, so it stays
 * unit-testable.
 */
class OcrTextAssembler private constructor() {

    companion object {

        /** Below this the recogniser's own guess counts as noise, whatever the line length. */
        const val MIN_CONFIDENCE = 0.6f

        /** Short lines carry no context to correct a misread, so they must clear this bar. */
        const val SHORT_LINE_CONFIDENCE = 0.8f

        /** A trimmed line shorter than this is "short" for the stricter confidence bar. */
        private const val SHORT_LINE_CHARS = 4

        private const val MIN_LINE_CHARS = 2

        const val LABEL_MINE = "我："
        const val LABEL_THEIRS = "对方："

        /** A line wider than this share of the image is treated as spanning the whole width. */
        private const val SPAN_RATIO = 0.82f

        /** How far past the centre a line must sit before its side is trusted. */
        private const val SIDE_MARGIN_RATIO = 0.06f

        /**
         * Sorts lines top-to-bottom (then left-to-right for lines sharing a row) and joins them.
         *
         * OCR does not guarantee reading order, and a chat transcript is meaningless out of order.
         * Lines the recogniser was unsure about ([MIN_CONFIDENCE]) are dropped here already: garbled
         * noise joined into the transcript poisons every stage after it.
         */
        fun assemble(lines: List<OcrLine>): String =
            lines
                .filter { it.text.isNotBlank() && it.confidence >= MIN_CONFIDENCE }
                .sortedWith(compareBy({ it.top }, { it.left }))
                .joinToString("\n") { it.text.trim() }

        /**
         * Drops lines that are almost certainly chrome or garble rather than conversation: single
         * stray characters and status-bar residue add noise without carrying meaning, a line the
         * recogniser itself is unsure about is more likely artefact than speech, and a line that is
         * nothing but a timestamp or a system notice is chat furniture, not speech.
         *
         * Short lines face a stricter bar: with only a couple of characters there is no context to
         * correct a misread, so they must clear [SHORT_LINE_CONFIDENCE] to survive.
         */
        fun isPlausibleDialogueLine(line: OcrLine): Boolean {
            if (line.confidence < MIN_CONFIDENCE) return false
            val text = line.text.trim()
            if (text.length < MIN_LINE_CHARS) return false
            if (isNoiseLine(text)) return false
            if (text.length < SHORT_LINE_CHARS && line.confidence < SHORT_LINE_CONFIDENCE) return false
            return true
        }

        /**
         * True when the whole line is chat chrome — a bare timestamp, date stamp or system notice —
         * rather than something somebody said.
         *
         * Every pattern is anchored end to end on purpose: "明天14:30开会" contains a timestamp but
         * is a real message, so only lines that are *nothing but* noise are dropped.
         */
        fun isNoiseLine(text: String): Boolean {
            val trimmed = text.trim()
            return NOISE_LINE_PATTERNS.any { it.matches(trimmed) }
        }

        /**
         * Whole-line chrome shapes, precompiled once: clock times, dates, relative days, weekday
         * stamps, month-day stamps, the stock chat-app system notices, and bare symbol runs.
         */
        private val NOISE_LINE_PATTERNS: List<Regex> = listOf(
            Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$"),
            Regex("^\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?$"),
            Regex("^(昨天|前天|今天|明天|后天|大前天|大后天)$"),
            Regex("^(周[一二三四五六日天]|星期[一二三四五六日天])$"),
            Regex("^\\d{1,2}月\\d{1,2}日$"),
            Regex("^(对方正在输入.*|以下为新消息|以上是打招呼的内容|添加了.*|撤回了一条消息)$"),
            Regex("^[\\s\\p{Punct}]+$")
        )

        /** Convenience: assemble only plausible lines. */
        fun assembleDialogue(lines: List<OcrLine>): String = assemble(lines.filter(::isPlausibleDialogueLine))

        /**
         * Assembles a chat-shaped transcript, inferring speakers from where the text sits.
         *
         * A bare transcription cannot be analysed: the dialogue parser needs `speaker: text`, and
         * without labels every line becomes an unknown speaker, which collapses the analysis into
         * the generic fallback. Chat apps place the two parties on opposite sides, so horizontal
         * alignment is the signal — right-aligned lines are the user's own messages, left-aligned
         * lines belong to the other party.
         *
         * Lines that span most of the width carry no positional signal and are left unattributed
         * rather than guessed at.
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
         * Requires both the centre *and* the nearer edge to fall on the same side, so a wide line
         * that merely leans one way is treated as unclear instead of being mislabelled.
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
    }
}
