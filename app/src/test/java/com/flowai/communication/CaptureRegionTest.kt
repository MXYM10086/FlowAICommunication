package com.flowai.communication

import com.flowai.communication.domain.CaptureRegion
import com.flowai.communication.system.OcrLine
import com.flowai.communication.system.OcrTextAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Region math and OCR text assembly — the two pure pieces of the capture pipeline. */
class CaptureRegionTest {

    @Test fun widthAndHeightComeFromTheCorners() {
        val r = CaptureRegion(10, 20, 110, 220)
        assertEquals(100, r.width)
        assertEquals(200, r.height)
    }

    @Test fun aUsefullySizedRegionIsValid() {
        assertTrue(CaptureRegion(0, 0, 400, 800).isValid())
    }

    @Test fun tinyRegionsAreRejected() {
        assertFalse(CaptureRegion(0, 0, 4, 400).isValid())
        assertFalse(CaptureRegion(0, 0, 400, 4).isValid())
        assertFalse(CaptureRegion(0, 0, 0, 0).isValid())
    }

    @Test fun invertedRegionsAreRejected() {
        // A backwards drag must not produce a negative-size crop.
        assertFalse(CaptureRegion(400, 800, 0, 0).isValid())
    }

    @Test fun clampKeepsTheRegionOnScreen() {
        val r = CaptureRegion(-50, -50, 2000, 5000).clampTo(1080, 2400)
        assertEquals(CaptureRegion(0, 0, 1080, 2400), r)
    }

    @Test fun clampLeavesAnInBoundsRegionAlone() {
        val r = CaptureRegion(100, 200, 300, 400)
        assertEquals(r, r.clampTo(1080, 2400))
    }

    @Test fun fullScreenHelperCoversEverything() {
        assertEquals(CaptureRegion(0, 0, 1080, 2400), CaptureRegion.fullScreen(1080, 2400))
    }
}

class OcrTextAssemblerTest {

    private fun line(text: String, top: Int, left: Int = 0) =
        OcrLine(text, left, top, left + 100, top + 40)

    @Test fun linesAreJoinedOnePerLineToMatchTheDialogueParser() {
        val text = OcrTextAssembler.assemble(listOf(line("对方：在吗", 0), line("我：在", 50)))
        assertEquals("对方：在吗\n我：在", text)
    }

    @Test fun outOfOrderLinesAreSortedTopToBottom() {
        // OCR does not guarantee reading order; a transcript out of order is meaningless.
        val text = OcrTextAssembler.assemble(
            listOf(line("第二句", 100), line("第一句", 20), line("第三句", 200))
        )
        assertEquals("第一句\n第二句\n第三句", text)
    }

    @Test fun linesSharingARowAreSortedLeftToRight() {
        val text = OcrTextAssembler.assemble(
            listOf(line("右侧", 10, left = 300), line("左侧", 10, left = 10))
        )
        assertEquals("左侧\n右侧", text)
    }

    @Test fun blankLinesAreDropped() {
        val text = OcrTextAssembler.assemble(listOf(line("有内容", 0), line("   ", 40), line("也有", 80)))
        assertEquals("有内容\n也有", text)
    }

    @Test fun lineTextIsTrimmed() {
        assertEquals("内容", OcrTextAssembler.assemble(listOf(line("  内容  ", 0))))
    }

    @Test fun nothingRecognisedYieldsEmptyText() {
        assertEquals("", OcrTextAssembler.assemble(emptyList()))
    }

    @Test fun dialogueFilterDropsSingleCharacterChrome() {
        // Stray single glyphs are usually icons or status-bar residue, not conversation.
        assertFalse(OcrTextAssembler.isPlausibleDialogueLine(line("A", 0)))
        assertTrue(OcrTextAssembler.isPlausibleDialogueLine(line("在吗", 0)))
    }

    @Test fun assembleDialogueKeepsOnlyPlausibleLines() {
        val text = OcrTextAssembler.assembleDialogue(
            listOf(line("5", 0), line("对方：今天能弄好吗", 40), line("•", 80))
        )
        assertEquals("对方：今天能弄好吗", text)
    }

    @Test fun assembledTextStillParsesAsSpeakerLabelledDialogue() {
        // The whole point of this format: the existing pipeline must accept it unchanged.
        val text = OcrTextAssembler.assembleDialogue(
            listOf(line("对方：这个东西今天能弄好吗？", 0), line("我：可能还差一点。", 40), line("对方：行吧。", 80))
        )
        val messages = com.flowai.communication.domain.PlainTextDialogueParser().parse(text)
        assertEquals(3, messages.size)
        assertEquals(com.flowai.communication.data.model.Speaker.OTHER, messages[0].speaker)
        assertEquals(com.flowai.communication.data.model.Speaker.ME, messages[1].speaker)
        assertEquals(com.flowai.communication.data.model.Speaker.OTHER, messages[2].speaker)
    }
}
