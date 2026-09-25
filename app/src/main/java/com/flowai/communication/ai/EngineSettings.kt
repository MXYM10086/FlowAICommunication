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
 * The wire protocol the configured endpoint speaks.
 *
 * "Support any model" is really "support any of the handful of protocols models hide behind":
 * almost every provider (DeepSeek, Qwen, GLM, Moonshot, OpenAI, a local Ollama…) accepts the
 * OpenAI-compatible shape, while Anthropic and Google ship their own. One selector here covers
 * all of them without a plugin system.
 */
enum class ApiFormat(val label: String) {
    /** `POST {url}` with `messages`/`choices` — DeepSeek, Qwen, GLM, Moonshot, OpenAI, Ollama… */
    OPENAI_COMPAT("OpenAI 兼容接口"),

    /** `POST {base}/v1/messages` with `x-api-key` — Claude models. */
    ANTHROPIC("Anthropic Messages"),

    /** `POST {base}/models/{model}:generateContent` — Gemini models. */
    GEMINI("Gemini generateContent")
}

/**
 * A provider the settings screen can fill in with one tap: protocol, endpoint and a current
 * model name. The key is never part of a preset — it stays the user's own credential.
 */
data class ProviderPreset(val name: String, val format: ApiFormat, val url: String, val model: String)

/** Common destinations, so "support various models" is a tap rather than a docs hunt. */
val PROVIDER_PRESETS = listOf(
    ProviderPreset("DeepSeek", ApiFormat.OPENAI_COMPAT, "https://api.deepseek.com/chat/completions", "deepseek-chat"),
    ProviderPreset("通义千问", ApiFormat.OPENAI_COMPAT, "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus"),
    ProviderPreset("智谱 GLM", ApiFormat.OPENAI_COMPAT, "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4-plus"),
    ProviderPreset("Moonshot", ApiFormat.OPENAI_COMPAT, "https://api.moonshot.cn/v1/chat/completions", "moonshot-v1-8k"),
    ProviderPreset("OpenAI", ApiFormat.OPENAI_COMPAT, "https://api.openai.com/v1/chat/completions", "gpt-4o"),
    ProviderPreset("Ollama 本机", ApiFormat.OPENAI_COMPAT, "http://localhost:11434/v1/chat/completions", "qwen2.5"),
    ProviderPreset("Claude", ApiFormat.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-4-5"),
    ProviderPreset("Gemini", ApiFormat.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash")
)

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
    /** The wire protocol [providerUrl] speaks; decides how requests are built. */
    val apiFormat: ApiFormat = ApiFormat.OPENAI_COMPAT,
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

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): EngineSettings {
        val defaults = EngineSettings()
        return EngineSettings(
            mode = runCatching {
                EngineMode.valueOf(prefs.getString(KEY_MODE, defaults.mode.name)!!)
            }.getOrDefault(defaults.mode),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            providerUrl = prefs.getString(KEY_PROVIDER_URL, defaults.providerUrl).orEmpty(),
            model = prefs.getString(KEY_MODEL, defaults.model).orEmpty().ifBlank { defaults.model },
            apiFormat = runCatching {
                ApiFormat.valueOf(prefs.getString(KEY_API_FORMAT, defaults.apiFormat.name)!!)
            }.getOrDefault(defaults.apiFormat),
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
            .putString(KEY_API_FORMAT, settings.apiFormat.name)
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
        return if (settings.canUseRemote) RemoteLlmService(
            settingsProvider = { load() },
            styleProvider = { ReplyStyleStore(appContext).load() }
        )
        else MockLlmService()
    }

    companion object {
        private const val PREFS_NAME = "flowai.engine"
        private const val KEY_MODE = "mode"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_PROVIDER_URL = "providerUrl"
        private const val KEY_MODEL = "model"
        private const val KEY_API_FORMAT = "apiFormat"
        private const val KEY_CONSENT = "consentedAt"

        /**
         * Engine factory for callers that only have a Context.
         *
         * [signature] changes when the configuration changes, which is how a caller knows to drop a
         * cached engine: the API engine holds the whole analysis after its first call, so reusing it
         * while nothing changed is what keeps the follow-up calls from going back to the fallback.
         */
        fun engineFactory(context: Context): Pair<() -> LlmService, () -> String> {
            val store = EngineSettingsStore(context.applicationContext)
            val styles = ReplyStyleStore(context.applicationContext)
            val signature = {
                val s = store.load()
                // The persona is part of what a cached analysis was produced with, so switching it
                // must drop the cached engine just like any other configuration change.
                "${s.mode}|${s.providerUrl}|${s.model}|${s.apiKey.hashCode()}|${s.consentedAt}|${s.apiFormat}|${styles.load().name}"
            }
            return ({ store.engineOrLocal() }) to signature
        }
    }
}
