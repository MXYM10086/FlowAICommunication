package com.flowai.communication

import com.flowai.communication.system.OcrLine
import com.flowai.communication.system.OcrTextAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Speaker inference for captured screens.
 *
 * The dialogue parser needs `speaker: text`; a bare transcription yields only unknown speakers and
 * the analysis collapses to its generic fallback. Chat apps separate the two parties horizontally,
 * so these tests pin that behaviour down.
 */
class OcrSpeakerAssemblyTest {

    private val width = 1000

    private fun line(text: String, left: Int, right: Int, top: Int = 0, confidence: Float = 1f) =
        OcrLine(text = text, left = left, top = top, right = right, bottom = top + 40, confidence = confidence)

    @Test
    fun `right aligned line is attributed to me`() {
        val out = OcrTextAssembler.assembleWithSpeakers(
            listOf(line("我明天给你", left = 700, right = 950)),
            width
        )
        assertEquals("我：我明天给你", out)
    }

    @Test
    fun `left aligned line is attributed to the other party`() {
        val out = OcrTextAssembler.assembleWithSpeakers(
            listOf(line("这个方案再改改", left = 50, right = 300)),
            width
        )
        assertEquals("对方：这个方案再改改", out)
    }

    @Test
    fun `a full conversation gets labels in reading order`() {
        val out = OcrTextAssembler.assembleWithSpeakers(
            listOf(
                line("今天能交吗", left = 60, right = 280, top = 100),
                line("还差一点", left = 720, right = 940, top = 200),
                line("明天上午给你", left = 640, right = 950, top = 300)
            ),
            width
        )
        assertEquals(
            "对方：今天能交吗\n我：还差一点\n我：明天上午给你",
            out
        )
    }

    @Test
    fun `a line spanning the width is left unattributed rather than guessed`() {
        val out = OcrTextAssembler.assembleWithSpeakers(
            listOf(line("这是一行几乎占满整个宽度的文字内容", left = 20, right = 980)),
            width
        )
        assertEquals("这是一行几乎占满整个宽度的文字内容", out)
    }

    @Test
    fun `lines near the centre are left unattributed`() {
        val out = OcrTextAssembler.assembleWithSpeakers(
            listOf(line("居中显示", left = 470, right = 530)),
            width
        )
        assertEquals("居中显示", out)
    }

    @Test
    fun `very short lines are dropped as chrome`() {
        val out = OcrTextAssembler.assembleWithSpeakers(
            listOf(
                line("1", left = 700, right = 720, top = 0),
                line("收到", left = 700, right = 800, top = 100)
            ),
            width
        )
        assertEquals("我：收到", out)
    }

    @Test
    fun `nothing recognisable yields an empty transcript`() {
        val out = OcrTextAssembler.assembleWithSpeakers(listOf(line("1", 0, 10)), width)
        assertEquals("", out)
    }

    @Test
    fun `an unknown width falls back to a plain transcript`() {
        val out = OcrTextAssembler.assembleWithSpeakers(
            listOf(line("今天能交吗", left = 60, right = 280)),
            imageWidth = 0
        )
        assertEquals("今天能交吗", out)
    }

    @Test
    fun `low confidence lines are dropped as noise`() {
        val out = OcrTextAssembler.assembleWithSpeakers(
            listOf(
                line("锟斤拷烫烫烫", left = 60, right = 280, top = 0, confidence = 0.35f),
                line("今天能交吗", left = 60, right = 280, top = 100)
            ),
            width
        )
        assertEquals("对方：今天能交吗", out)
    }

    @Test
    fun `assemble also drops sub-threshold lines`() {
        val out = OcrTextAssembler.assemble(
            listOf(
                line("%%%%", left = 0, right = 100, top = 0, confidence = 0.2f),
                line("正常的一行文字", left = 0, right = 300, top = 50)
            )
        )
        assertEquals("正常的一行文字", out)
    }

    @Test
    fun `short lines survive only with high confidence`() {
        val out = OcrTextAssembler.assembleWithSpeakers(
            listOf(
                line("收到", left = 700, right = 800, top = 0, confidence = 0.7f),
                line("好的", left = 700, right = 800, top = 100, confidence = 0.85f)
            ),
            width
        )
        assertEquals("我：好的", out)
    }

    @Test
    fun `whole-line timestamps and system notices are dropped as chrome`() {
        val out = OcrTextAssembler.assembleWithSpeakers(
            listOf(
                line("14:30", left = 60, right = 160, top = 0),
                line("昨天", left = 60, right = 120, top = 50),
                line("周一", left = 60, right = 120, top = 100),
                line("对方正在输入...", left = 60, right = 300, top = 150),
                line("今天能交吗", left = 60, right = 280, top = 200)
            ),
            width
        )
        assertEquals("对方：今天能交吗", out)
    }

    @Test
    fun `a timestamp inside a real message does not make it noise`() {
        val out = OcrTextAssembler.assembleWithSpeakers(
            listOf(
                line("明天14:30开会", left = 60, right = 320, top = 0),
                line("好的明天见", left = 700, right = 880, top = 100)
            ),
            width
        )
        assertEquals("对方：明天14:30开会\n我：好的明天见", out)
    }

    @Test
    fun `isNoiseLine matches whole lines only`() {
        assertTrue(OcrTextAssembler.isNoiseLine("14:30"))
        assertTrue(OcrTextAssembler.isNoiseLine("昨天"))
        assertTrue(OcrTextAssembler.isNoiseLine("周一"))
        assertTrue(OcrTextAssembler.isNoiseLine("对方正在输入..."))
        assertFalse(OcrTextAssembler.isNoiseLine("明天14:30开会"))
        assertFalse(OcrTextAssembler.isNoiseLine("好的明天见"))
    }

    @Test
    fun `the assembled transcript parses into the two speakers`() {
        val transcript = OcrTextAssembler.assembleWithSpeakers(
            listOf(
                line("今天能交吗", left = 60, right = 280, top = 100),
                line("还差一点", left = 720, right = 940, top = 200)
            ),
            width
        )
        val messages = com.flowai.communication.domain.PlainTextDialogueParser().parse(transcript)
        assertEquals(2, messages.size)
        assertTrue("first message should be the other party", messages[0].text.contains("今天能交吗"))
        assertTrue("second message should be mine", messages[1].text.contains("还差一点"))
        assertTrue(
            "speakers must differ, otherwise analysis degrades to the fallback",
            messages[0].speaker != messages[1].speaker
        )
    }
}
