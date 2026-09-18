package com.flowai.communication

import com.flowai.communication.domain.SharedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers payload handling for the two external entry points:
 *  - ACTION_SEND / ACTION_SEND_MULTIPLE (share sheet)
 *  - ACTION_PROCESS_TEXT (selection toolbar)
 *
 * Shapes covered here were established from real senders, including a payload captured from a
 * physical device where WeChat shares multi-selected messages as `message/rfc822`.
 */
class SharedTextTest {

    private val chat = "对方：这个东西今天能弄好吗？"
    private val reply = "我：可能还差一点。"

    // ---- plain text ----

    @Test fun plainTextIsUsedAsIs() {
        assertEquals(chat, SharedText.extract(chat, null))
    }

    @Test fun plainTextWinsOverHtmlWhenBothPresent() {
        assertEquals(chat, SharedText.extract(chat, "<b>$chat</b>"))
    }

    @Test fun weChatStyleHtmlOnlyPayloadIsRecovered() {
        val html = "<p>$chat</p><p>$reply</p>"
        assertEquals("$chat\n$reply", SharedText.extract(null, html))
    }

    @Test fun htmlInTheTextExtraIsFlattened() {
        assertEquals(chat, SharedText.extract("<div>$chat</div>", null))
    }

    @Test fun brBecomesNewlineSoMessagesStayOnePerLine() {
        assertEquals("$chat\n$reply", SharedText.extract(null, "$chat<br>$reply"))
    }

    @Test fun entitiesAreDecoded() {
        assertEquals("A & B <tag> \"q\"", SharedText.extract(null, "A &amp; B &lt;tag&gt; &quot;q&quot;"))
    }

    @Test fun numericEntitiesAreDecoded() {
        assertEquals("中", SharedText.extract(null, "&#20013;"))
        assertEquals("中", SharedText.extract(null, "&#x4E2D;"))
    }

    @Test fun nbspBecomesSpaceNotGarbage() {
        assertEquals("小王 小李", SharedText.extract(null, "小王&nbsp;小李"))
    }

    @Test fun scriptAndStyleContentIsDropped() {
        assertEquals("正文", SharedText.extract(null, "<style>p{color:red}</style><p>正文</p><script>alert(1)</script>"))
    }

    @Test fun linkMarkupKeepsItsVisibleText() {
        assertEquals("查看详情", SharedText.extract(null, "<a href=\"https://x.test\">查看详情</a>"))
    }

    @Test fun blankAndEmptyPayloadsYieldNull() {
        assertNull(SharedText.extract(null, null))
        assertNull(SharedText.extract("", ""))
        assertNull(SharedText.extract("   ", "  "))
    }

    @Test fun markupWithNoVisibleTextYieldsNullRatherThanTags() {
        assertNull(SharedText.extract(null, "<p></p><br/>"))
    }

    @Test fun markupDetectionDoesNotMangleTextWithAngleBrackets() {
        val raw = "我：如果 a < b > c 就不行"
        assertEquals(raw, SharedText.extract(raw, null))
    }

    @Test fun multiLineChatSurvivesRoundTrip() {
        val body = "$chat\n$reply"
        assertEquals(body, SharedText.extract(body, null))
    }

    @Test fun extractedHtmlKeepsSpeakerLabelsParseable() {
        val out = SharedText.extract(null, "<p>小王：数据我今晚发</p>")
        assertTrue(out!!.startsWith("小王："))
    }

    // ---- action resolution ----

    @Test fun sendActionResolvesToShareEntry() {
        val r = SharedText.resolve(SharedText.ACTION_SEND, "text/plain", chat, null, null)
        assertEquals(SharedText.Entry.SHARE, r?.entry)
        assertEquals(chat, r?.text)
    }

    @Test fun sendWithHtmlTypeStillResolves() {
        val r = SharedText.resolve(SharedText.ACTION_SEND, "text/html", null, "<p>$chat</p>", null)
        assertEquals(SharedText.Entry.SHARE, r?.entry)
        assertEquals(chat, r?.text)
    }

    @Test fun processTextActionResolvesToProcessTextEntry() {
        val r = SharedText.resolve(SharedText.ACTION_PROCESS_TEXT, "text/plain", null, null, chat)
        assertEquals(SharedText.Entry.PROCESS_TEXT, r?.entry)
        assertEquals(chat, r?.text)
    }

    @Test fun processTextIgnoresSendExtras() {
        assertNull(SharedText.resolve(SharedText.ACTION_PROCESS_TEXT, "text/plain", chat, null, null))
    }

    @Test fun processTextWithBlankSelectionIsRejected() {
        assertNull(SharedText.resolve(SharedText.ACTION_PROCESS_TEXT, "text/plain", null, null, "   "))
    }

    @Test fun unrelatedActionsAreIgnored() {
        assertNull(SharedText.resolve("android.intent.action.MAIN", null, null, null, null))
        assertNull(SharedText.resolve(null, null, chat, null, null))
    }

    @Test fun processTextKeepsMultiLineSelectionIntact() {
        val body = "$chat\n$reply"
        val r = SharedText.resolve(SharedText.ACTION_PROCESS_TEXT, "text/plain", null, null, body)
        assertEquals(body, r?.text)
    }

    // ---- WeChat's real payload shape, captured from a device ----

    @Test fun weChatMultiSelectShareIsAccepted() {
        // Real device capture: WeChat sends multi-selected chat messages as
        //   action=SEND_MULTIPLE, type=message/rfc822, EXTRA_TEXT=<the messages>
        // A text/* MIME gate rejected this, so the share silently did nothing.
        val body = "$chat\n$reply"
        val r = SharedText.resolve(SharedText.ACTION_SEND_MULTIPLE, "message/rfc822", body, null, null)
        assertEquals(SharedText.Entry.SHARE, r?.entry)
        assertEquals(body, r?.text)
    }

    @Test fun messageRfc822IsAcceptedForSingleSendToo() {
        val r = SharedText.resolve(SharedText.ACTION_SEND, "message/rfc822", chat, null, null)
        assertEquals(chat, r?.text)
    }

    @Test fun nonTextMimeWithRealTextIsAccepted() {
        // The gate is "does it carry text", not "is the MIME in a list".
        for (mime in listOf("message/rfc822", "application/octet-stream", "multipart/mixed", null)) {
            val r = SharedText.resolve(SharedText.ACTION_SEND, mime, chat, null, null)
            assertEquals("text should be accepted for $mime", chat, r?.text)
        }
    }

    @Test fun shareWithoutTextExtrasIsRejected() {
        // An image/file share has nothing to analyze.
        assertNull(SharedText.resolve(SharedText.ACTION_SEND, "image/png", null, null, null, null))
        assertNull(SharedText.resolve(SharedText.ACTION_SEND_MULTIPLE, "image/*", null, null, null, null))
    }

    // ---- ClipData fallback ----

    @Test fun clipDataIsUsedWhenShareHasNoExtras() {
        // androidx ShareCompat.IntentReader never reads ClipData, so a ClipData-only share would
        // otherwise be silently dropped.
        val r = SharedText.resolve(SharedText.ACTION_SEND, "text/plain", null, null, null, chat)
        assertEquals(SharedText.Entry.SHARE, r?.entry)
        assertEquals(chat, r?.text)
    }

    @Test fun extrasWinOverClipDataWhenBothPresent() {
        val r = SharedText.resolve(SharedText.ACTION_SEND, "text/plain", "对方：extras", null, null, "对方：clip")
        assertEquals("对方：extras", r?.text)
    }

    @Test fun blankTextExtrasAreNotBackfilledFromClipData() {
        // Regression, confirmed on device: without a guard, a blank EXTRA_TEXT let the ClipData
        // fallback import the platform's component-name artifact ("-n") as chat text.
        assertNull(SharedText.resolve(SharedText.ACTION_SEND, "text/plain", " ", null, null, "-n"))
        assertNull(SharedText.resolve(SharedText.ACTION_SEND, "text/plain", "", null, null, "-n"))
        assertNull(SharedText.resolve(SharedText.ACTION_SEND, "text/plain", "\u00A0", null, null, "-n"))
    }

    @Test fun shortJunkInClipDataIsNotImported() {
        assertNull(SharedText.resolve(SharedText.ACTION_SEND, "text/plain", null, null, null, "-n"))
    }

    @Test fun clipDataFallbackAppliesToProcessText() {
        val r = SharedText.resolve(SharedText.ACTION_PROCESS_TEXT, "text/plain", null, null, null, "对方：划词")
        assertEquals(SharedText.Entry.PROCESS_TEXT, r?.entry)
        assertEquals("对方：划词", r?.text)
    }

    // ---- EXTRA_TEXT shape normalisation ----

    @Test fun normalizeItemsAcceptsArrayList() {
        assertEquals(listOf("a", "b"), SharedText.normalizeItems(arrayListOf("a", "b")))
    }

    @Test fun normalizeItemsAcceptsPlainArray() {
        assertEquals(listOf("a", "b"), SharedText.normalizeItems(arrayOf("a", "b")))
    }

    @Test fun normalizeItemsAcceptsCharSequenceArrayList() {
        val items = ArrayList<CharSequence>().apply { add("a"); add("b") }
        assertEquals(listOf("a", "b"), SharedText.normalizeItems(items))
    }

    @Test fun normalizeItemsHandlesSingleStringAndNull() {
        assertEquals(listOf("only"), SharedText.normalizeItems("only"))
        assertEquals(emptyList<String>(), SharedText.normalizeItems(null))
        assertEquals(emptyList<String>(), SharedText.normalizeItems(arrayOf<String>()))
    }

    @Test fun normalizeItemsSkipsEmptyEntries() {
        assertEquals(listOf("a", "b"), SharedText.normalizeItems(arrayListOf("a", "", "b")))
    }

    @Test fun sendMultipleJoinsItemsIntoOneChatBody() {
        val r = SharedText.resolve(
            SharedText.ACTION_SEND_MULTIPLE, "text/plain", null, null, null, null,
            listOf(chat, reply)
        )
        assertEquals(SharedText.Entry.SHARE, r?.entry)
        assertEquals("$chat\n$reply", r?.text)
    }

    @Test fun sendMultipleWithNoMimeTypeIsAccepted() {
        val r = SharedText.resolve(
            SharedText.ACTION_SEND_MULTIPLE, null, null, null, null, null,
            listOf("对方：在吗", "我：在")
        )
        assertEquals("对方：在吗\n我：在", r?.text)
    }

    @Test fun emptySendMultipleItemsYieldNull() {
        assertNull(SharedText.resolve(SharedText.ACTION_SEND_MULTIPLE, "text/plain", null, null, null, null, emptyList()))
    }

    @Test fun shareWithNoMimeTypeIsAccepted() {
        val r = SharedText.resolve(SharedText.ACTION_SEND, null, chat, null, null)
        assertEquals(SharedText.Entry.SHARE, r?.entry)
        assertEquals(chat, r?.text)
    }

    @Test fun weChatStyleMultipleEndToEndFromRawExtra() {
        // Mirrors the real handler: read the raw extra, normalise, then resolve.
        val raw: Any = arrayListOf(chat, reply, "对方：行吧。")
        val r = SharedText.resolve(
            SharedText.ACTION_SEND_MULTIPLE, "message/rfc822", null, null, null, null,
            SharedText.normalizeItems(raw)
        )
        assertEquals(SharedText.Entry.SHARE, r?.entry)
        assertEquals("$chat\n$reply\n对方：行吧。", r?.text)
    }
}
