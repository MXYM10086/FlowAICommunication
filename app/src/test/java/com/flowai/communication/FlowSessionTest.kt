package com.flowai.communication

import androidx.lifecycle.ViewModelStore
import com.flowai.communication.data.model.ActionObject
import com.flowai.communication.data.model.SourceType
import com.flowai.communication.data.repository.DemoConversations
import com.flowai.communication.ui.FlowViewModel
import com.flowai.communication.ui.Page
import org.junit.Assert.*
import org.junit.Test

class FlowSessionTest {
    private fun replySession() = FlowViewModel().apply {
        openInput(DemoConversations.A)
        analyze()
        choose(requireNotNull(analysis).actions.first())
    }

    private fun assertNoContext(model: FlowViewModel) {
        assertEquals(Page.HOME, model.page)
        assertEquals("", model.input)
        assertNull(model.analysis)
        assertNull(model.selected)
        assertNull(model.output)
        assertNull(model.error)
    }

    private fun assertEnded(model: FlowViewModel) {
        assertNoContext(model)
        assertFalse(model.clearedNotice.isNullOrBlank())
    }

    @Test fun explicitEndReleasesConversationAndReplies() {
        val model = replySession()
        assertEquals(Page.ACTION, model.page)
        assertTrue(requireNotNull(model.analysis).capsule.messages.isNotEmpty())
        assertEquals(3, requireNotNull(model.output).replies.size)

        model.endSession()

        assertEnded(model)
    }

    @Test fun endingAnUnanalysedDraftReleasesItsText() {
        val model = FlowViewModel()
        model.openInput()
        model.edit("我：这是一条尚未提交的私密草稿")
        assertTrue(model.input.isNotEmpty())
        assertNull(model.analysis)

        model.endSession()

        assertEnded(model)
    }

    @Test fun endingAnInvalidInputClearsBothDraftAndError() {
        val model = FlowViewModel()
        model.openInput("   ")
        model.analyze()
        assertNotNull(model.error)
        assertEquals(Page.INPUT, model.page)

        model.endSession()

        assertEnded(model)
    }

    @Test fun actionBackReleasesActionButKeepsCurrentAnalysis() {
        val model = replySession()
        val analysis = requireNotNull(model.analysis)

        model.back()

        assertEquals(Page.ANALYSIS, model.page)
        assertSame(analysis, model.analysis)
        assertEquals(DemoConversations.A, model.input)
        assertNull(model.selected)
        assertNull(model.output)
    }

    @Test fun inputBackEndsTheSessionAndClearsItsError() {
        val model = FlowViewModel()
        model.openInput(" ")
        model.analyze()
        assertNotNull(model.error)

        model.back()

        assertEnded(model)
    }

    @Test fun analysisBackEndsTheSession() {
        val model = FlowViewModel()
        model.openInput(DemoConversations.A)
        model.analyze()
        assertEquals(Page.ANALYSIS, model.page)

        model.back()

        assertEnded(model)
    }

    @Test fun replyEditChangesOnlyTheChosenStyleAndEndReleasesIt() {
        val model = replySession()
        val original = requireNotNull(model.output).replies
        val chosenStyle = original.first().style
        val editedText = "这条编辑后的回复包含本次专属信息"

        model.editReply(chosenStyle, editedText)

        val edited = requireNotNull(model.output).replies
        assertEquals(editedText, edited.single { it.style == chosenStyle }.text)
        assertEquals(
            original.filter { it.style != chosenStyle },
            edited.filter { it.style != chosenStyle }
        )
        model.endSession()
        assertEnded(model)
    }

    @Test fun endingDemoBReleasesEventAndTasksTogether() {
        val model = FlowViewModel()
        model.openInput(DemoConversations.B)
        model.analyze()
        model.choose(requireNotNull(model.analysis).actions.first())
        val objects = requireNotNull(model.output).objects
        assertEquals(1, objects.filterIsInstance<ActionObject.Event>().size)
        assertEquals(2, objects.filterIsInstance<ActionObject.Task>().size)

        model.endSession()

        assertEnded(model)
    }

    @Test fun openingAnEmptyInputStartsFreshAndDiscardsPreviousResult() {
        val model = replySession()

        model.openInput()

        assertEquals(Page.INPUT, model.page)
        assertEquals("", model.input)
        assertNull(model.analysis)
        assertNull(model.selected)
        assertNull(model.output)
        assertNull(model.error)
        assertNull(model.clearedNotice)
    }

    @Test fun openingNewTextDiscardsThePreviousConversation() {
        val model = replySession()

        model.openInput(DemoConversations.B)

        assertEquals(Page.INPUT, model.page)
        assertEquals(DemoConversations.B, model.input)
        assertNull(model.analysis)
        assertNull(model.selected)
        assertNull(model.output)
        assertNull(model.error)
        model.analyze()
        assertEquals(DemoConversations.B, requireNotNull(model.analysis).capsule.rawText)
    }

    @Test fun openingNewInputClearsAnEarlierValidationError() {
        val model = FlowViewModel()
        model.openInput(" ")
        model.analyze()
        assertNotNull(model.error)

        model.openInput()

        assertEquals(Page.INPUT, model.page)
        assertEquals("", model.input)
        assertNull(model.error)
        assertNull(model.analysis)
    }

    @Test fun repeatedEndIsSafeAndTheNextSessionStartsEmpty() {
        val model = replySession()
        model.endSession()
        model.endSession()
        assertEnded(model)

        model.openInput()

        assertEquals(Page.INPUT, model.page)
        assertEquals("", model.input)
        assertNull(model.analysis)
        assertNull(model.selected)
        assertNull(model.output)
        assertNull(model.error)
        assertNull(model.clearedNotice)
        model.edit(DemoConversations.B)
        model.analyze()
        assertEquals(Page.ANALYSIS, model.page)
        assertEquals(DemoConversations.B, requireNotNull(model.analysis).capsule.rawText)
    }

    @Test fun anOldActionCannotRecreateOutputAfterEnding() {
        val model = replySession()
        val oldAction = requireNotNull(model.selected)
        model.endSession()

        model.choose(oldAction)
        model.editReply("自然", "不应在已结束会话中恢复的回复")

        assertEnded(model)
    }

    @Test fun aNewViewModelDoesNotRestoreAnotherInstancesContent() {
        val previous = replySession()
        assertNotNull(previous.analysis)
        assertNotNull(previous.output)

        val fresh = FlowViewModel()

        assertNoContext(fresh)
        assertNull(fresh.clearedNotice)
    }

    @Test fun clearingTheViewModelStoreReleasesSensitiveFields() {
        val model = replySession()
        model.editReply("自然", "销毁页面时也必须释放这段编辑内容")
        val store = ViewModelStore()
        store.put("flow-session", model)
        assertNotNull(model.analysis)
        assertNotNull(model.output)

        store.clear()

        assertNoContext(model)
    }

    @Test fun sharedTextOpensInputWithShareSource() {
        val model = FlowViewModel()
        model.consumeShare("我：这是从微信分享来的内容")
        assertEquals(Page.INPUT, model.page)
        assertEquals("我：这是从微信分享来的内容", model.input)
        assertEquals(SourceType.SHARE, model.sourceType)
    }

    @Test fun repeatedIdenticalShareDoesNotResetAnInProgressAnalysis() {
        val model = FlowViewModel()
        model.consumeShare(DemoConversations.A)
        model.analyze()
        assertEquals(Page.ANALYSIS, model.page)

        model.consumeShare(DemoConversations.A)

        assertEquals(Page.ANALYSIS, model.page)
        assertNotNull(model.analysis)
    }
}
