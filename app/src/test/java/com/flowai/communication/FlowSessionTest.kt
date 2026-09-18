package com.flowai.communication

import androidx.lifecycle.ViewModelStore
import com.flowai.communication.data.model.ActionObject
import com.flowai.communication.data.model.SourceType
import com.flowai.communication.data.repository.DemoConversations
import com.flowai.communication.domain.CaptureSession
import com.flowai.communication.domain.CaptureState
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
        // The "content cleared" notice survives into the next draft on purpose, so the user still
        // sees confirmation that the previous session's content is gone.
        assertNotNull("clear notice should persist until the next analysis", model.clearedNotice)
        model.edit(DemoConversations.B)
        model.analyze()
        assertEquals(Page.ANALYSIS, model.page)
        assertNull("analysing a new draft clears the stale notice", model.clearedNotice)
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

    @Test fun sharedTextOpensInputWithShareSource() {        val model = FlowViewModel()
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

    // ---- the session notices the user must not be told lies about ----

    @Test fun aShareThatReplacesUnfinishedWorkSaysSo() {
        // Previously the in-progress analysis was discarded silently. It is still replaced, but the
        // user is now told, and the session records why it ended.
        val model = FlowViewModel()
        model.openInput(DemoConversations.A)
        model.analyze()
        assertEquals(Page.ANALYSIS, model.page)

        model.consumeShare(DemoConversations.B)

        assertEquals(Page.INPUT, model.page)
        assertEquals(DemoConversations.B, model.input)
        assertEquals("上一段分析已被新的内容替换", model.supersededNotice)
    }

    @Test fun aShareIntoAnEmptyDraftIsNotAnnouncedAsAReplacement() {
        val model = FlowViewModel()
        model.openInput()
        assertNull(model.analysis)

        model.consumeShare(DemoConversations.B)

        assertNull("nothing was lost, so nothing to announce", model.supersededNotice)
    }

    @Test fun theSupersededNoticeClearsOnceTheNewContentIsAnalysed() {
        val model = FlowViewModel()
        model.openInput(DemoConversations.A)
        model.analyze()
        model.consumeShare(DemoConversations.B)
        assertNotNull(model.supersededNotice)

        model.analyze()

        assertNull(model.supersededNotice)
    }

    @Test fun anExpiredSessionIsReleasedAndReported() {
        val session = CaptureSession(timeoutMs = 1_000L)
        val model = FlowViewModel(capture = session)
        model.openInput(DemoConversations.A, SourceType.SCREENSHOT)
        model.analyze()
        assertTrue(session.isHoldingContext)

        // Not yet due.
        assertFalse(model.releaseIfExpired(now = session.startedAt + 500L))
        assertNotNull("analysis must survive until the deadline", model.analysis)

        assertTrue(model.releaseIfExpired(now = session.startedAt + 1_000L))

        assertNull("expired context is released", model.analysis)
        assertNull(model.selected)
        assertNull(model.output)
        assertEquals("", model.input)
        assertEquals(Page.HOME, model.page)
        assertEquals("本次内容已超时清除", model.clearedNotice)
        assertFalse(session.isHoldingContext)
    }

    @Test fun endingASessionReleasesTheCaptureSession() {
        val session = CaptureSession()
        val model = FlowViewModel(capture = session)
        model.openInput(DemoConversations.A, SourceType.SHARE)
        assertTrue(session.isHoldingContext)

        model.endSession()

        assertFalse("endSession must release the session", session.isHoldingContext)
        assertEquals(CaptureState.IDLE, session.state)
    }

    @Test fun clearingTheViewModelReleasesTheCaptureSession() {
        val session = CaptureSession()
        val model = FlowViewModel(capture = session)
        model.openInput(DemoConversations.A, SourceType.SHARE)
        assertTrue(session.isHoldingContext)

        val store = ViewModelStore()
        store.put("k", model)
        store.clear()

        assertFalse("onCleared must release the session", session.isHoldingContext)
    }

    @Test fun captureStateIsObservableForTheUi() {
        val model = FlowViewModel()
        assertEquals(CaptureState.IDLE, model.captureState)

        model.openInput(DemoConversations.A, SourceType.SHARE)
        assertEquals(CaptureState.ACTIVE, model.captureState)

        model.endSession()
        assertEquals(CaptureState.IDLE, model.captureState)
    }
}
