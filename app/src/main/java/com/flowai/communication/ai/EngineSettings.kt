package com.flowai.communication.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * Engine configuration and the user's consent to send conversation text off the device.
 *
 * Two separate things on purpose:
 *
 *  - [endpoint] says *where* an engine lives. Blank means the app analyses locally and nothing
 *    ever leaves the phone.
 *  - [consentedAt] records that the user was told their text would be sent to that endpoint and
 *    agreed. Configuration without consent still analyses locally, because sending a private
 *    conversation somewhere is the user's decision, not a setting's.
 */
data class EngineSettings(
    /** Base URL of the relay that fronts the model provider. Blank means "use the local engine". */
    val endpoint: String = "",
    /** Sent as a bearer token to the relay. Only needed when the relay requires one. */
    val accessToken: String = "",
    /** Epoch millis when the user agreed to send text to [endpoint]; 0 means never agreed. */
    val consentedAt: Long = 0L
) {
    val isConfigured: Boolean get() = endpoint.isNotBlank()

    val hasConsent: Boolean get() = consentedAt > 0L

    /** True only when text may actually be sent off the device. */
    val canUseRemote: Boolean get() = isConfigured && hasConsent
}

/** Reads and writes [EngineSettings] in private preferences. */
class EngineSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): EngineSettings = EngineSettings(
        endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty().trim(),
        accessToken = prefs.getString(KEY_TOKEN, "").orEmpty(),
        consentedAt = prefs.getLong(KEY_CONSENT, 0L)
    )

    fun save(settings: EngineSettings) {
        val previous = load()
        prefs.edit()
            .putString(KEY_ENDPOINT, settings.endpoint.trim())
            .putString(KEY_TOKEN, settings.accessToken)
            // Changing the destination invalidates the old agreement: the user consented to a
            // particular endpoint, not to "wherever it points next".
            .putLong(
                KEY_CONSENT,
                if (settings.endpoint.trim() == previous.endpoint) settings.consentedAt else 0L
            )
            .apply()
    }

    /** Records the user's agreement to send text to the currently configured endpoint. */
    fun recordConsent(at: Long) {
        prefs.edit().putLong(KEY_CONSENT, at).apply()
    }

    /**
     * Picks the engine for a call.
     *
     * Falls back to the local engine whenever nothing is configured *or* the user has not agreed to
     * send text out, so an unconfigured or unconsented install never touches the network.
     */
    fun engineOrLocal(): LlmService {
        val settings = load()
        return if (settings.canUseRemote) {
            RemoteLlmService(settingsProvider = { load() })
        } else {
            MockLlmService()
        }
    }

    companion object {
        private const val PREFS_NAME = "flowai.engine"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_TOKEN = "accessToken"
        private const val KEY_CONSENT = "consentedAt"

        /**
         * Engine factory for callers that only have a Context.
         *
         * Resolved per call by design, so changing the configuration takes effect on the next
         * analysis without rebuilding anything.
         */
        fun engineFactory(context: Context): () -> LlmService {
            val store = EngineSettingsStore(context.applicationContext)
            return { store.engineOrLocal() }
        }
    }
}
