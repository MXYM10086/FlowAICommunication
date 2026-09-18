package com.flowai.communication.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * The two ways this app can analyse a conversation.
 *
 *  - [LOCAL]  — on device, offline, nothing leaves the phone. Always available.
 *  - [REMOTE] — calls the model API directly, which needs a key.
 *
 * There is deliberately no third option. An earlier revision added a self-hosted relay mode for
 * keeping the key off the device; it was more machinery than the product needs.
 */
enum class EngineMode { LOCAL, REMOTE }

/**
 * Engine configuration.
 *
 * The key lives in this app's private preferences, entered by the user rather than compiled in.
 * It is a credential: do not commit it. Anyone who unpacks an APK you hand out can read whatever
 * is inside it, which is the one caveat to remember before sharing a build.
 */
data class EngineSettings(
    val mode: EngineMode = EngineMode.LOCAL,
    val apiKey: String = "",
    val providerUrl: String = DEFAULT_PROVIDER_URL,
    val model: String = DEFAULT_MODEL,
    /** Epoch millis when the user agreed to send text off the device; 0 means never agreed. */
    val consentedAt: Long = 0L
) {
    /** True when the selected mode has what it needs to run. */
    val isConfigured: Boolean
        get() = mode == EngineMode.LOCAL || apiKey.isNotBlank()

    val hasConsent: Boolean get() = consentedAt > 0L

    /** True when text may actually be sent off the device. */
    val canUseRemote: Boolean get() = mode == EngineMode.REMOTE && isConfigured && hasConsent

    companion object {
        const val DEFAULT_PROVIDER_URL = "https://api.deepseek.com/chat/completions"
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}

/** Reads and writes [EngineSettings] in private preferences. */
class EngineSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): EngineSettings {
        val defaults = EngineSettings()
        return EngineSettings(
            mode = runCatching {
                EngineMode.valueOf(prefs.getString(KEY_MODE, defaults.mode.name)!!)
            }.getOrDefault(defaults.mode),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            providerUrl = prefs.getString(KEY_PROVIDER_URL, defaults.providerUrl).orEmpty(),
            model = prefs.getString(KEY_MODEL, defaults.model).orEmpty().ifBlank { defaults.model },
            consentedAt = prefs.getLong(KEY_CONSENT, 0L)
        )
    }

    fun save(settings: EngineSettings) {
        val previous = load()
        // Consent is tied to a specific destination: changing where text goes invalidates the old
        // agreement, because the user agreed to that destination and not to wherever it points next.
        val sameDestination = settings.mode == previous.mode &&
            settings.providerUrl.trim() == previous.providerUrl.trim()

        prefs.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putString(KEY_API_KEY, settings.apiKey.trim())
            .putString(KEY_PROVIDER_URL, settings.providerUrl.trim())
            .putString(KEY_MODEL, settings.model.trim())
            .putLong(KEY_CONSENT, if (sameDestination) settings.consentedAt else 0L)
            .apply()
    }

    /** Records the user's agreement to send text to the model API. */
    fun recordConsent(at: Long) {
        prefs.edit().putLong(KEY_CONSENT, at).apply()
    }

    /**
     * Picks the engine for a call.
     *
     * Local unless the API is switched on, given a key, and agreed to — so a fresh install never
     * reaches the network.
     */
    fun engineOrLocal(): LlmService {
        val settings = load()
        return if (settings.canUseRemote) RemoteLlmService(settingsProvider = { load() })
        else MockLlmService()
    }

    companion object {
        private const val PREFS_NAME = "flowai.engine"
        private const val KEY_MODE = "mode"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_PROVIDER_URL = "providerUrl"
        private const val KEY_MODEL = "model"
        private const val KEY_CONSENT = "consentedAt"

        /**
         * Engine factory for callers that only have a Context.
         *
         * Resolved per call by design, so a settings change takes effect on the next analysis.
         */
        fun engineFactory(context: Context): () -> LlmService {
            val store = EngineSettingsStore(context.applicationContext)
            return { store.engineOrLocal() }
        }
    }
}
