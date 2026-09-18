package com.flowai.communication

import com.flowai.communication.domain.SharedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the "share from WeChat does nothing" defect. WeChat and other clients attach styled text
 * as EXTRA_HTML_TEXT or as text/html, which the previous text/plain-only handling dropped.
 */
class SharedTextTest {

    @Test fun plainTextIsUsedAsIs() {
        assertEquals("对方：今天能弄好吗？", SharedText.extract("对方：今天能弄好吗？", null))
    }

    @Test fun plainTextWinsOverHtmlWhenBothPresent() {
        assertEquals(
            "对方：今天能弄好吗？",
            SharedText.extract("对方：今天能弄好吗？", "<b>对方：今天能弄好吗？</b>")
        )
    }

    @Test fun weChatStyleHtmlOnlyPayloadIsRecovered() {
        // This is the shape that previously produced an empty input: EXTRA_TEXT absent, HTML present.
        val html = "<p>对方：这个东西今天能弄好吗？</p><p>我：可能还差一点。</p>"
        val out = SharedText.extract(null, html)
        assertEquals("对方：这个东西今天能弄好吗？\n我：可能还差一点。", out)
    }

    @Test fun htmlInTheTextExtraIsFlattened() {
        val out = SharedText.extract("<div>老师：明天下午三点在 A203 开会</div>", null)
        assertEquals("老师：明天下午三点在 A203 开会", out)
    }

    @Test fun brBecomesNewlineSoMessagesStayOnePerLine() {
        val out = SharedText.extract(null, "对方：你好<br>我：你好")
        assertEquals("对方：你好\n我：你好", out)
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
        val out = SharedText.extract(null, "<style>p{color:red}</style><p>正文</p><script>alert(1)</script>")
        assertEquals("正文", out)
    }

    @Test fun linkMarkupKeepsItsVisibleText() {
        val out = SharedText.extract(null, "<a href=\"https://x.test\">查看详情</a>")
        assertEquals("查看详情", out)
    }

    @Test fun blankAndEmptyPayloadsYieldNull() {
        assertNull(SharedText.extract(null, null))
        assertNull(SharedText.extract("", ""))
        assertNull(SharedText.extract("   ", "  "))
    }

    @Test fun markupWithNoVisibleTextYieldsNullRatherThanTags() {
        assertNull(SharedText.extract(null, "<p></p><br/>"))
    }

    @Test fun markupDetectionDoesNotMangleTextThatMerelyContainsAngleBrackets() {
        // A chat line like "a < b > c" is not markup and must survive untouched.
        val raw = "我：如果 a < b > c 就不行"
        assertEquals(raw, SharedText.extract(raw, null))
    }

    @Test fun multiLineChatSurvivesRoundTrip() {
        val chat = "老师：明天下午三点在 A203 开会，小王准备 PPT。\n我：收到。"
        assertEquals(chat, SharedText.extract(chat, null))
    }

    @Test fun extractedHtmlKeepsSpeakerLabelsParseable() {
        // The parsed result must still satisfy the speaker-label convention the engine relies on.
        val out = SharedText.extract(null, "<p>小王：数据我今晚发</p>")
        assertTrue(out!!.startsWith("小王："))
    }

    // ---- action resolution: which entry point delivered the payload ----

    @Test fun sendActionResolvesToShareEntry() {
        val r = SharedText.resolve(SharedText.ACTION_SEND, "text/plain", "对方：在吗", null, null)
        assertEquals(SharedText.Entry.SHARE, r?.entry)
        assertEquals("对方：在吗", r?.text)
    }

    @Test fun sendWithHtmlTypeStillResolves() {
        val r = SharedText.resolve(SharedText.ACTION_SEND, "text/html", null, "<p>对方：在吗</p>", null)
        assertEquals(SharedText.Entry.SHARE, r?.entry)
        assertEquals("对方：在吗", r?.text)
    }

    @Test fun nonTextShareIsRejected() {
        assertNull(SharedText.resolve(SharedText.ACTION_SEND, "image/png", "t", "<p>h</p>", null))
    }

    @Test fun processTextActionResolvesToProcessTextEntry() {
        // The selection toolbar delivers the user's selection in EXTRA_PROCESS_TEXT.
        val r = SharedText.resolve(SharedText.ACTION_PROCESS_TEXT, "text/plain", null, null, "对方：这个东西今天能弄好吗？")
        assertEquals(SharedText.Entry.PROCESS_TEXT, r?.entry)
        assertEquals("对方：这个东西今天能弄好吗？", r?.text)
    }

    @Test fun processTextIgnoresSendExtras() {
        // A PROCESS_TEXT intent must not fall back to share extras; only the selection counts.
        assertNull(SharedText.resolve(SharedText.ACTION_PROCESS_TEXT, "text/plain", "对方：在吗", null, null))
    }

    @Test fun processTextWithBlankSelectionIsRejected() {
        assertNull(SharedText.resolve(SharedText.ACTION_PROCESS_TEXT, "text/plain", null, null, "   "))
    }

    @Test fun unrelatedActionsAreIgnored() {
        assertNull(SharedText.resolve("android.intent.action.MAIN", null, null, null, null))
        assertNull(SharedText.resolve(null, null, "对方：在吗", null, null))
    }

    @Test fun processTextKeepsMultiLineSelectionIntact() {
        val chat = "对方：明天下午三点开会\n我：收到"
        val r = SharedText.resolve(SharedText.ACTION_PROCESS_TEXT, "text/plain", null, null, chat)
        assertEquals(chat, r?.text)
    }

    // ---- ClipData fallback: some senders leave the extras null entirely ----

    @Test fun clipDataIsUsedWhenShareHasNoExtras() {
        // Verified against androidx ShareCompat.IntentReader source: it never reads ClipData, so a
        // ClipData-only share would otherwise be silently dropped.
        val r = SharedText.resolve(SharedText.ACTION_SEND, "text/plain", null, null, null, "对方：在吗")
        assertEquals(SharedText.Entry.SHARE, r?.entry)
        assertEquals("对方：在吗", r?.text)
    }

    @Test fun extrasWinOverClipDataWhenBothPresent() {
        val r = SharedText.resolve(SharedText.ACTION_SEND, "text/plain", "对方：extras", null, null, "对方：clip")
        assertEquals("对方：extras", r?.text)
    }

    @Test fun blankTextExtrasAreNotBackfilledFromClipData() {
        // Regression, confirmed on device: with no guard, a blank EXTRA_TEXT made the ClipData
        // fallback import the platform's component-name artifact ("-n") as chat text.
        assertNull(SharedText.resolve(SharedText.ACTION_SEND, "text/plain", " ", null, null, "-n"))
        assertNull(SharedText.resolve(SharedText.ACTION_SEND, "text/plain", "", null, null, "-n"))
        assertNull(SharedText.resolve(SharedText.ACTION_SEND, "text/plain", "\u00A0", null, null, "-n"))
    }

    @Test fun shortJunkInClipDataIsNotImported() {
        // The ClipData channel is a fallback, so it must carry plausible content to be trusted.
        assertNull(SharedText.resolve(SharedText.ACTION_SEND, "text/plain", null, null, null, "-n"))
    }

    @Test fun realChatTextInClipDataIsStillImported() {
        val r = SharedText.resolve(SharedText.ACTION_SEND, "text/plain", null, null, null, "对方：在吗")
        assertEquals(SharedText.Entry.SHARE, r?.entry)
        assertEquals("对方：在吗", r?.text)
    }

    @Test fun clipDataFallbackAlsoAppliesToProcessText() {
        val r = SharedText.resolve(SharedText.ACTION_PROCESS_TEXT, "text/plain", null, null, null, "对方：划词")
        assertEquals(SharedText.Entry.PROCESS_TEXT, r?.entry)
        assertEquals("对方：划词", r?.text)
    }

    @Test fun blankProcessTextSelectionIsNotBackfilledFromClipData() {
        assertNull(SharedText.resolve(SharedText.ACTION_PROCESS_TEXT, "text/plain", null, null, "  ", "-n"))
    }

    @Test fun nonTextShareIsRejectedEvenWithClipDataText() {
        assertNull(SharedText.resolve(SharedText.ACTION_SEND, "image/png", null, null, null, "not chat text"))
    }
}
