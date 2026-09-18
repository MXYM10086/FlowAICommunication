package com.flowai.communication.domain

/**
 * Pulls the body out of an incoming text entry point and flattens it to plain text.
 *
 * Two entry points are supported:
 *
 *  - `ACTION_SEND` — the share sheet. Senders are not uniform: WeChat and others frequently attach
 *    styled text as `EXTRA_HTML_TEXT` (the platform's fallback when text cannot be expressed as
 *    plain text) or as `text/html`, which a `text/plain` intent filter never matches.
 *  - `ACTION_PROCESS_TEXT` — the text-selection toolbar. The selection arrives in
 *    `EXTRA_PROCESS_TEXT` and is plain, because the platform only offers PROCESS_TEXT for
 *    `text/plain`.
 *
 * For shares: `EXTRA_TEXT` wins when it carries content; otherwise `EXTRA_HTML_TEXT` is used; a
 * body that is itself markup is flattened too.
 *
 * Kept free of Android APIs so it is directly unit-testable, hence the action strings are literals.
 */
object SharedText {

    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"

    /** Which system entry point delivered the payload. */
    enum class Entry { SHARE, PROCESS_TEXT }

    /** A payload accepted from another app, already flattened to plain text. */
    data class Incoming(val text: String, val entry: Entry)

    /**
     * Resolves an incoming intent into an accepted payload.
     *
     * - `ACTION_SEND` (share sheet): body is `EXTRA_TEXT`, falling back to `EXTRA_HTML_TEXT`.
     * - `ACTION_PROCESS_TEXT` (selection toolbar): the user's selection is `EXTRA_PROCESS_TEXT`
     *   and is always plain text, because the platform only offers PROCESS_TEXT for
     *   `text/plain`.
     *
     * `clipText` is the text of `Intent.getClipData()`'s first item, when present. Some senders put
     * the payload ONLY in `ClipData` and leave the extras null — `ShareCompat.IntentReader` does not
     * read `ClipData` at all, so it has to be handled explicitly.
     *
     * Returns null when the intent is not a text entry point or carries nothing usable.
     */
    fun resolve(
        action: String?,
        mimeType: String?,
        text: CharSequence?,
        html: CharSequence?,
        processed: CharSequence?,
        clipText: CharSequence? = null
    ): Incoming? {
        return when (action) {
            ACTION_SEND -> {
                // A non-text share (an image, a file) is not something this MVP can analyze.
                if (mimeType != null && !mimeType.startsWith("text/")) return null
                // ClipData is only consulted when the extras yielded nothing usable: some senders put
                // the payload there and leave the extras null. When the extras are present but
                // unusable the share is empty, and ClipData junk — the platform puts the component
                // name there for command-line intents — must not be imported.
                val body = extract(text, html)
                val fromClip = if (body == null && hasPlausibleContent(clipText)) extract(clipText, null) else null
                (body ?: fromClip)?.let { Incoming(it, Entry.SHARE) }
            }
            ACTION_PROCESS_TEXT -> {
                val body = extract(processed, null)
                val fromClip = if (body == null && hasPlausibleContent(clipText)) extract(clipText, null) else null
                (body ?: fromClip)?.let { Incoming(it, Entry.PROCESS_TEXT) }
            }
            else -> null
        }
    }

    /**
     * True when a value carries enough non-whitespace content to be worth importing.
     *
     * Used only to vet the `ClipData` fallback, which is never the primary channel. The platform
     * puts short non-content strings there for command-line intents (e.g. the component name "-n"),
     * so a plausible-length floor keeps that junk out without needing to judge the text itself.
     */
    private fun hasPlausibleContent(value: CharSequence?): Boolean {
        if (value == null) return false
        var count = 0
        for (element in value) {
            if (!Character.isWhitespace(element) && ++count >= MIN_CLIP_CONTENT_CHARS) return true
        }
        return false
    }

    private const val MIN_CLIP_CONTENT_CHARS = 4

    fun extract(textExtra: CharSequence?, htmlExtra: CharSequence?): String? {
        val plain = textExtra?.toString()?.takeIf { it.isNotBlank() }
        if (plain != null && !looksLikeMarkup(plain)) return plain.trim()

        val html = htmlExtra?.toString()?.takeIf { it.isNotBlank() }
            ?: plain?.takeIf { looksLikeMarkup(it) }
            ?: return null

        return stripMarkup(html).trim().ifBlank { null }
    }

    private fun looksLikeMarkup(value: String): Boolean {        val head = value.trimStart().take(120)
        return head.startsWith("<") && head.contains('>')
    }

    /** Flattens a small, well-formed subset of HTML into readable plain text. */
    fun stripMarkup(html: String): String {
        var s = html
        // Line-breaking elements become real newlines so a chat stays one-message-per-line.
        s = Regex("(?i)<\\s*br\\s*/?\\s*>").replace(s, "\n")
        s = Regex("(?i)</\\s*(p|div|li|tr|h[1-6])\\s*>").replace(s, "\n")
        s = Regex("(?i)<\\s*script[^>]*>.*?</\\s*script\\s*>", RegexOption.DOT_MATCHES_ALL).replace(s, "")
        s = Regex("(?i)<\\s*style[^>]*>.*?</\\s*style\\s*>", RegexOption.DOT_MATCHES_ALL).replace(s, "")
        s = Regex("<[^>]+>").replace(s, "")
        s = decodeEntities(s)
        // Collapse the runs of blank lines that markup leaves behind.
        s = Regex("[ \\t\\x0B\\f\\r]+").replace(s, " ")
        s = Regex(" *\n *").replace(s, "\n")
        s = Regex("\n{3,}").replace(s, "\n\n")
        return s.trim()
    }

    private fun decodeEntities(s: String): String {
        var out = s
        val named = mapOf(
            "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
            "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&mdash;" to "—", "&ndash;" to "–"
        )
        named.forEach { (entity, replacement) -> out = out.replace(entity, replacement, ignoreCase = true) }
        out = Regex("&#(\\d+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.let { code -> String(Character.toChars(code)) } ?: m.value
        }
        return Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { code -> String(Character.toChars(code)) } ?: m.value
        }
    }
}
