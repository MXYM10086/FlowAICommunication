package com.flowai.communication

import com.flowai.communication.data.model.SourceType
import com.flowai.communication.domain.CaptureEndReason
import com.flowai.communication.domain.CaptureSession
import com.flowai.communication.domain.CaptureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session is the Just-in-Time Context contract: context is acquired on user action and must be
 * released afterwards. These tests pin the release guarantees, since a regression here means
 * holding a transcript or screenshot longer than promised.
 */
class CaptureSessionTest {

    private val timeout = CaptureSession.DEFAULT_TIMEOUT_MS

    @Test fun startsIdleAndHoldsNothing() {
        val s = CaptureSession()
        assertEquals(CaptureState.IDLE, s.state)
        assertFalse(s.isHoldingContext)
        assertNull(s.heldText())
        assertNull(s.source)
    }

    @Test fun beginHoldsContextAndRecordsSource() {
        val s = CaptureSession()
        val superseded = s.begin("我：你好", SourceType.SHARE, now = 1_000L)

        assertNull("first session supersedes nothing", superseded)
        assertEquals(CaptureState.ACTIVE, s.state)
        assertTrue(s.isHoldingContext)
        assertEquals("我：你好", s.heldText())
        assertEquals(SourceType.SHARE, s.source)
        assertEquals(1_000L, s.startedAt)
    }

    @Test fun endReleasesHeldText() {
        val s = CaptureSession()
        s.begin("我：这是一段私密内容", SourceType.SHARE, now = 0L)

        assertTrue(s.end(now = 10L))

        assertEquals(CaptureState.IDLE, s.state)
        assertFalse(s.isHoldingContext)
        assertNull("held text must be gone", s.heldText())
        assertNull(s.source)
    }

    @Test fun endOnIdleSessionIsANoOp() {
        val s = CaptureSession()
        assertFalse(s.end())
        assertEquals(CaptureState.IDLE, s.state)
    }

    @Test fun secondEndDoesNothing() {
        val s = CaptureSession()
        s.begin("x", SourceType.TEXT, now = 0L)
        assertTrue(s.end(now = 1L))
        assertFalse("already released", s.end(now = 2L))
    }

    @Test fun beginSupersedesAnActiveSessionAndReportsIt() {
        val s = CaptureSession()
        s.begin("old context", SourceType.SHARE, now = 0L)

        val reason = s.begin("new context", SourceType.PROCESS_TEXT, now = 5L)

        assertEquals(CaptureEndReason.SUPERSEDED, reason)
        assertEquals("only the new context is held", "new context", s.heldText())
        assertEquals(SourceType.PROCESS_TEXT, s.source)
    }

    @Test fun beginAfterEndSupersedesNothing() {
        val s = CaptureSession()
        s.begin("first", SourceType.SHARE, now = 0L)
        s.end(now = 1L)

        assertNull(s.begin("second", SourceType.SHARE, now = 2L))
        assertEquals("second", s.heldText())
    }

    // ---- timeout ----

    @Test fun activeSessionExpiresAtItsDeadline() {
        val s = CaptureSession(timeoutMs = 1_000L)
        s.begin("ctx", SourceType.SCREENSHOT, now = 0L)

        assertFalse("just before deadline", s.isExpired(now = 999L))
        assertTrue("at deadline", s.isExpired(now = 1_000L))
        assertTrue("well past deadline", s.isExpired(now = 5_000L))
    }

    @Test fun onExpiredReleasesContextExactlyOnce() {
        val s = CaptureSession(timeoutMs = 1_000L)
        s.begin("截图内容", SourceType.SCREENSHOT, now = 0L)

        assertFalse("not yet due", s.onExpired(now = 500L))
        assertTrue(s.onExpired(now = 1_000L))

        assertEquals(CaptureState.EXPIRED, s.state)
        assertNull("expired context must be released", s.heldText())
        assertFalse(s.isHoldingContext)
        assertFalse("must not fire twice", s.onExpired(now = 2_000L))
    }

    @Test fun expiryIsDistinctFromUserEndSoCallersCanReportIt() {
        val s = CaptureSession(timeoutMs = 100L)
        s.begin("ctx", SourceType.SCREENSHOT, now = 0L)
        s.onExpired(now = 100L)
        assertEquals(CaptureState.EXPIRED, s.state)

        val u = CaptureSession(timeoutMs = 100L)
        u.begin("ctx", SourceType.SCREENSHOT, now = 0L)
        u.end(now = 50L)
        assertEquals("user end returns to idle", CaptureState.IDLE, u.state)
    }

    @Test fun acknowledgeExpiryReturnsToIdle() {
        val s = CaptureSession(timeoutMs = 100L)
        s.begin("ctx", SourceType.SCREENSHOT, now = 0L)
        s.onExpired(now = 100L)

        s.acknowledgeExpiry()

        assertEquals(CaptureState.IDLE, s.state)
    }

    @Test fun acknowledgeExpiryDoesNotDisturbAnActiveSession() {
        val s = CaptureSession(timeoutMs = 100L)
        s.begin("ctx", SourceType.SHARE, now = 0L)

        s.acknowledgeExpiry()

        assertEquals(CaptureState.ACTIVE, s.state)
        assertEquals("ctx", s.heldText())
    }

    @Test fun remainingMsCountsDownAndNeverGoesNegative() {
        val s = CaptureSession(timeoutMs = 1_000L)
        s.begin("ctx", SourceType.SHARE, now = 0L)

        assertEquals(1_000L, s.remainingMs(now = 0L))
        assertEquals(400L, s.remainingMs(now = 600L))
        assertEquals(0L, s.remainingMs(now = 1_000L))
        assertEquals(0L, s.remainingMs(now = 9_999L))
    }

    @Test fun remainingMsIsZeroWhenIdle() {
        assertEquals(0L, CaptureSession().remainingMs(now = 0L))
    }

    @Test fun defaultTimeoutIsLongEnoughToReadAnAnalysisButNotIndefinite() {
        assertTrue("at least a minute", CaptureSession.DEFAULT_TIMEOUT_MS >= 60_000L)
        assertTrue("under ten minutes", CaptureSession.DEFAULT_TIMEOUT_MS <= 10 * 60_000L)
    }

    @Test fun everyEntryPointCanStartASession() {
        // All V2 entry points share this lifecycle; none of them may need special-casing here.
        for (source in SourceType.values()) {
            val s = CaptureSession()
            s.begin("ctx", source, now = 0L)
            assertEquals(CaptureState.ACTIVE, s.state)
            assertEquals(source, s.source)
            s.end(now = 1L)
            assertNull(s.heldText())
        }
    }
}
