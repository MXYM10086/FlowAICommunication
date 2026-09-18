package com.flowai.communication

import com.flowai.communication.data.model.SourceType
import com.flowai.communication.data.repository.DemoConversations
import com.flowai.communication.domain.CONSUMED_SHARE_EXPIRY_MS
import com.flowai.communication.domain.InMemoryConsumedShareStore
import com.flowai.communication.ui.FlowViewModel
import com.flowai.communication.ui.Page
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers the fix for: after process death, Android re-delivers the original SEND intent from the
 * task record, which used to silently re-import chat text the user had already ended the session on.
 * A fresh ViewModel with a surviving store stands in for a recreated process.
 */
class ShareConsumptionTest {

    @Test fun reDeliveredShareDoesNotReopenInputInANewProcess() {
        val store = InMemoryConsumedShareStore()
        val first = FlowViewModel(store)
        first.consumeShare("我：这是从微信分享来的私密内容")
        assertEquals(Page.INPUT, first.page)

        // Simulate process death + task recreation: new ViewModel, same surviving store.
        val recreated = FlowViewModel(store)
        recreated.consumeShare("我：这是从微信分享来的私密内容")

        assertEquals(Page.HOME, recreated.page)
        assertEquals("", recreated.input)
        assertNull(recreated.analysis)
    }

    @Test fun aGenuinelyNewShareStillOpensInput() {
        val store = InMemoryConsumedShareStore()
        FlowViewModel(store).consumeShare(("我：" + "x".repeat(40)))

        val recreated = FlowViewModel(store)
        recreated.consumeShare(("我：" + "y".repeat(40)))

        assertEquals(Page.INPUT, recreated.page)
        assertTrue(recreated.input.startsWith("我：yyy"))
    }

    @Test fun shareIsConsumedOnlyOncePerDistinctText() {
        val store = InMemoryConsumedShareStore()
        val model = FlowViewModel(store)
        model.consumeShare(DemoConversations.A)
        model.analyze()
        assertEquals(Page.ANALYSIS, model.page)

        // Re-delivery of the same payload must not wipe an analysis already in progress.
        model.consumeShare(DemoConversations.A)

        assertEquals(Page.ANALYSIS, model.page)
        assertNotNull(model.analysis)
    }

    @Test fun endedSessionIsNotResurrectedByTaskRecreation() {
        val store = InMemoryConsumedShareStore()
        val first = FlowViewModel(store)
        first.consumeShare(DemoConversations.A)
        first.analyze()
        first.endSession()
        assertNull(first.analysis)

        // Task recreation re-delivers the same intent; the session must stay ended.
        val recreated = FlowViewModel(store)
        recreated.consumeShare(DemoConversations.A)

        assertEquals(Page.HOME, recreated.page)
        assertNull(recreated.analysis)
        assertNull(recreated.selected)
        assertNull(recreated.output)
    }

    @Test fun staleMarkerFromAPreviousSessionDoesNotBlockImport() {
        val store = InMemoryConsumedShareStore()
        val text = "我：很久以前导入过同样的内容"

        // Marker recorded long ago (expired) must not swallow a fresh share of the same text.
        store.markConsumed(text, now = 0L)
        assertFalse("an expired marker must not count as consumed", store.wasConsumed(text))

        // Re-marking now makes it current again.
        store.markConsumed(text)
        assertTrue(store.wasConsumed(text))
    }

    @Test fun storeOnlyMatchesTheExactText() {
        val store = InMemoryConsumedShareStore()
        store.markConsumed("我：第一条")
        assertFalse(store.wasConsumed("我：第二条"))
        assertFalse(store.wasConsumed("我：第一条 "))
    }

    @Test fun defaultExpiryIsLongEnoughToCoverTaskRecreation() {
        assertTrue(CONSUMED_SHARE_EXPIRY_MS >= 5 * 60 * 1000L)
    }

    @Test fun freshStoreWithoutHistoryImportsNormally() {
        val model = FlowViewModel(InMemoryConsumedShareStore())
        model.consumeShare(DemoConversations.B)
        assertEquals(Page.INPUT, model.page)
        assertEquals(DemoConversations.B, model.input)
    }

    // ---- selection-toolbar entry (ACTION_PROCESS_TEXT) ----

    @Test fun processTextSelectionOpensInputWithItsOwnSource() {
        val model = FlowViewModel()
        model.consumeShare("对方：这个东西今天能弄好吗？", SourceType.PROCESS_TEXT)

        assertEquals(Page.INPUT, model.page)
        assertEquals("对方：这个东西今天能弄好吗？", model.input)
        assertEquals(SourceType.PROCESS_TEXT, model.sourceType)
    }

    @Test fun processTextSelectionIsDedupedAcrossRecreationLikeAShare() {
        val store = InMemoryConsumedShareStore()
        FlowViewModel(store).consumeShare("对方：在吗", SourceType.PROCESS_TEXT)

        val recreated = FlowViewModel(store)
        recreated.consumeShare("对方：在吗", SourceType.PROCESS_TEXT)

        assertEquals(Page.HOME, recreated.page)
    }

    @Test fun aTypedTextSourceCannotBeInjectedAsAnExternalEntry() {
        // Guards the entry point: only real external sources may reset the session.
        val model = FlowViewModel()
        model.consumeShare("对方：在吗", SourceType.TEXT)
        assertEquals(Page.HOME, model.page)
    }
}
