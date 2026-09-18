package com.flowai.communication.domain

import com.flowai.communication.data.model.SourceType

/**
 * Lifecycle of a single Just-in-Time context acquisition.
 *
 * The product rule is "acquire on user action, release when the task is done". Every V2 entry
 * point (share, selection toolbar, floating overlay, screenshot OCR, accessibility) is a way of
 * starting one of these sessions, so the lifecycle lives here instead of being re-implemented per
 * entry point.
 *
 * Deliberately free of Android APIs, so it is directly unit-testable and cannot silently hold a
 * platform resource. Platform resources (an overlay window, a MediaProjection, a bitmap) are owned
 * by the caller and released in response to [end] / [onExpired].
 *
 * Time is injected rather than read from the clock so timeout behaviour is testable.
 */
enum class CaptureState {
    /** No session; no context is being held. */
    IDLE,

    /** A session is open and its context is held in memory. */
    ACTIVE,

    /** A session was open but hit its deadline; its context must be treated as released. */
    EXPIRED
}

/** Why a session ended, so callers can log/notify accurately. */
enum class CaptureEndReason {
    /** The user finished or dismissed the session explicitly. */
    USER_ENDED,

    /** The session hit its deadline and was ended without user action. */
    TIMED_OUT,

    /** A new session superseded it (e.g. a second share arrived). */
    SUPERSEDED
}

class CaptureSession(
    /** How long a session may stay open before it must be released. */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    var state: CaptureState = CaptureState.IDLE
        private set

    var source: SourceType? = null
        private set

    var startedAt: Long = 0L
        private set

    private var payload: String? = null

    /** True while context is held and therefore must be released. */
    val isHoldingContext: Boolean get() = state == CaptureState.ACTIVE

    /** Text held by the current session, or null when nothing is held. */
    fun heldText(): String? = if (state == CaptureState.ACTIVE) payload else null

    /**
     * Opens a session, releasing any previous one first.
     *
     * Returns the reason the previous session ended, or null if there was none — callers use this
     * to release whatever platform resource the earlier session owned.
     */
    fun begin(text: String, source: SourceType, now: Long = System.currentTimeMillis()): CaptureEndReason? {
        val previous = if (state == CaptureState.ACTIVE) CaptureEndReason.SUPERSEDED else null
        clearPayload()
        state = CaptureState.ACTIVE
        this.source = source
        startedAt = now
        payload = text
        return previous
    }

    /** Ends the session and releases the held context. Returns false if nothing was active. */
    fun end(reason: CaptureEndReason = CaptureEndReason.USER_ENDED, now: Long = System.currentTimeMillis()): Boolean {
        if (state != CaptureState.ACTIVE) return false
        clearPayload()
        // Only a timeout is a distinct terminal state; other reasons mean the context is simply
        // released and the app is idle again.
        state = if (reason == CaptureEndReason.TIMED_OUT) CaptureState.EXPIRED else CaptureState.IDLE
        source = null
        return true
    }

    /** True when an active session has passed its deadline. */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        state == CaptureState.ACTIVE && now - startedAt >= timeoutMs

    /**
     * Releases an over-deadline session. Safe to call repeatedly (e.g. from a periodic check).
     * Returns true when this call is what ended the session.
     */
    fun onExpired(now: Long = System.currentTimeMillis()): Boolean =
        if (isExpired(now)) end(CaptureEndReason.TIMED_OUT, now) else false

    /** Remaining milliseconds before the deadline; 0 when idle, expired, or already past it. */
    fun remainingMs(now: Long = System.currentTimeMillis()): Long =
        if (state != CaptureState.ACTIVE) 0L else (timeoutMs - (now - startedAt)).coerceAtLeast(0L)

    /** Returns to IDLE from EXPIRED so a new session can start from a clean state. */
    fun acknowledgeExpiry() {
        if (state == CaptureState.EXPIRED) state = CaptureState.IDLE
    }

    private fun clearPayload() {
        payload = null
        startedAt = 0L
    }

    companion object {
        /**
         * Long enough for a user to read an analysis, short enough that an abandoned session does
         * not hold a screenshot or transcript indefinitely.
         */
        const val DEFAULT_TIMEOUT_MS = 2 * 60 * 1000L
    }
}
