package com.flowai.communication.domain

import android.content.Context

/**
 * Remembers which shared text has already been imported, so the same share is not imported twice.
 *
 * Why this exists: when the process is killed and its task is later recreated, Android re-delivers
 * the original `ACTION_SEND` intent from the task record — `EXTRA_TEXT` included. Mutating that
 * intent (`removeExtra`) does **not** reach the task record, so stripping the extra does not help.
 * The app therefore has to remember on its own that this payload was already consumed.
 *
 * Privacy: only a 64-bit hash of the text and a timestamp are stored — never the text itself.
 * Entries self-expire after [expiryMs], so a deliberate re-share of the same text still works.
 */
interface ConsumedShareStore {
    fun wasConsumed(text: String): Boolean
    fun markConsumed(text: String, now: Long = System.currentTimeMillis())
}

/** Default 15 min: long enough to cover task recreation, short enough to allow a deliberate re-share. */
const val CONSUMED_SHARE_EXPIRY_MS = 15 * 60 * 1000L

/** Non-sensitive 64-bit fingerprint of the shared text. */
internal fun shareFingerprint(text: String): Int = text.hashCode()

private class ConsumedEntry(private var fingerprint: Int, private var consumedAt: Long) {
    fun matches(text: String, expiryMs: Long, now: Long): Boolean =
        fingerprint == shareFingerprint(text) && now - consumedAt <= expiryMs

    fun record(text: String, now: Long) {
        fingerprint = shareFingerprint(text)
        consumedAt = now
    }
}

/** Session-scoped: nothing outlives the ViewModel. Used by tests and as the safe default. */
class InMemoryConsumedShareStore(private val expiryMs: Long = CONSUMED_SHARE_EXPIRY_MS) : ConsumedShareStore {
    private var entry: ConsumedEntry? = null

    override fun wasConsumed(text: String): Boolean =
        entry?.matches(text, expiryMs, System.currentTimeMillis()) == true

    override fun markConsumed(text: String, now: Long) {
        entry?.record(text, now) ?: run { entry = ConsumedEntry(shareFingerprint(text), now) }
    }
}

/** Survives process death, which is the case that actually matters for this bug. */
class PrefsConsumedShareStore(
    context: Context,
    private val expiryMs: Long = CONSUMED_SHARE_EXPIRY_MS
) : ConsumedShareStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun wasConsumed(text: String): Boolean =
        entry()?.matches(text, expiryMs, System.currentTimeMillis()) == true

    override fun markConsumed(text: String, now: Long) {
        prefs.edit()
            .putInt(KEY_FINGERPRINT, shareFingerprint(text))
            .putLong(KEY_CONSUMED_AT, now)
            .apply()
    }

    private fun entry(): ConsumedEntry? {
        if (!prefs.contains(KEY_FINGERPRINT)) return null
        return ConsumedEntry(prefs.getInt(KEY_FINGERPRINT, 0), prefs.getLong(KEY_CONSUMED_AT, 0L))
    }

    private companion object {
        const val PREFS = "flowai.share"
        const val KEY_FINGERPRINT = "consumed.fingerprint"
        const val KEY_CONSUMED_AT = "consumed.at"
    }
}
