package com.flowai.communication

import com.flowai.communication.data.model.SourceType
import com.flowai.communication.data.repository.DemoConversations
import com.flowai.communication.ui.FlowViewModel
import com.flowai.communication.ui.Page
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * The capture preview step: recognised text is held for editing and confirmation before it becomes
 * a session, while share and text-selection payloads keep going straight to the input page.
 */
class OcrPreviewFlowTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test fun captureTextLandsOnThePreviewInsteadOfASession() {
        val model = FlowViewModel()
        model.showOcrPreview("我：截屏来的文本")

        assertEquals(Page.OCR_PREVIEW, model.page)
        assertEquals("我：截屏来的文本", model.ocrDraft)
        // Nothing is analysed or even sessioned until the user confirms.
        assertEquals("", model.input)
        assertNull(model.analysis)
    }

    @Test fun editingTheDraftStaysOnThePreview() {
        val model = FlowViewModel()
        model.showOcrPreview("对方：识别错的字")
        model.editOcrDraft("对方：识别对了的字")

        assertEquals(Page.OCR_PREVIEW, model.page)
        assertEquals("对方：识别对了的字", model.ocrDraft)
    }

    @Test fun submittingAnalysesTheDraftAsAScreenshotSessionAndDropsIt() {
        val model = FlowViewModel()
        model.showOcrPreview(DemoConversations.A)
        model.editOcrDraft(DemoConversations.A)

        model.submitOcrDraft()

        // Leaving the preview clears the draft; the text became a normal screenshot session and
        // was analysed right away.
        assertEquals("", model.ocrDraft)
        assertEquals(SourceType.SCREENSHOT, model.sourceType)
        assertEquals(Page.ANALYSIS, model.page)
        assertTrue(requireNotNull(model.analysis).capsule.messages.isNotEmpty())
    }

    @Test fun leavingThePreviewDiscardsTheDraft() {
        val model = FlowViewModel()
        model.showOcrPreview("我：还没确认的内容")

        model.back()

        assertEquals(Page.HOME, model.page)
        assertEquals("", model.ocrDraft)
    }

    @Test fun shareAndProcessTextSkipThePreview() {
        val model = FlowViewModel()
        model.consumeShare(DemoConversations.A, SourceType.SHARE)

        assertEquals(Page.INPUT, model.page)
        assertEquals("", model.ocrDraft)
    }
}
