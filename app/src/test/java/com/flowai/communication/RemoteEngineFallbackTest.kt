package com.flowai.communication

import com.flowai.communication.ai.EngineMode
import com.flowai.communication.ai.EngineSettings
import com.flowai.communication.ai.MockLlmService
import com.flowai.communication.ai.RemoteLlmService
import com.flowai.communication.data.model.ContextCapsule
import com.flowai.communication.data.model.SourceType
import com.flowai.communication.data.repository.DemoConversations
import com.flowai.communication.domain.PlainTextDialogueParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the promise that nothing is uploaded unless the user switched the API on, supplied a key
 * *and* agreed to send text.
 *
 * The assertions are about the gating decision, not about network behaviour — a unit test cannot
 * prove what did or did not reach a socket. What it can prove is that the engine never looks
 * uploadable, which is the condition the request path sits behind.
 */
class RemoteEngineFallbackTest {

    private val parser = PlainTextDialogueParser()

    private fun capsule(text: String) = ContextCapsule(
        sourceType = SourceType.TEXT,
        messages = parser.parse(text),
        rawText = text
    )

    private fun service(settings: EngineSettings) =
        RemoteLlmService(settingsProvider = { settings }, fallback = MockLlmService())

    @Test
    fun `a fresh install is not configured and has not consented`() {
        val settings = EngineSettings()
        assertEquals(EngineMode.LOCAL, settings.mode)
        assertFalse(settings.canUseRemote)
    }

    @Test
    fun `local mode never reports as uploadable even with a key present`() {
        val settings = EngineSettings(mode = EngineMode.LOCAL, apiKey = "sk-x", consentedAt = 1L)
        assertFalse("local mode must not upload", settings.canUseRemote)
    }

    @Test
    fun `remote mode without a key is not configured`() {
        val settings = EngineSettings(mode = EngineMode.REMOTE, apiKey = "", consentedAt = 1L)
        assertFalse(settings.isConfigured)
        assertFalse(settings.canUseRemote)
    }

    @Test
    fun `remote mode with a key but no consent must not upload`() {
        val settings = EngineSettings(mode = EngineMode.REMOTE, apiKey = "sk-x", consentedAt = 0L)
        assertTrue(settings.isConfigured)
        assertFalse(settings.hasConsent)
        assertFalse("configured is not the same as consented", settings.canUseRemote)
    }

    @Test
    fun `only key plus consent permits upload`() {
        val settings = EngineSettings(mode = EngineMode.REMOTE, apiKey = "sk-x", consentedAt = 1L)
        assertTrue(settings.canUseRemote)
    }

    @Test
    fun `an unconfigured engine analyses locally`() = runTest {
        val state = service(EngineSettings()).build(capsule(DemoConversations.A))
        assertEquals("任务完成进度", state.topic)
    }

    @Test
    fun `an unconsented engine analyses locally`() = runTest {
        val settings = EngineSettings(mode = EngineMode.REMOTE, apiKey = "sk-x", consentedAt = 0L)
        val state = service(settings).build(capsule(DemoConversations.A))
        assertEquals("任务完成进度", state.topic)
    }

    @Test
    fun `a failed request falls back instead of failing the analysis`() = runTest {
        // Port 1 on loopback refuses connections immediately, so this exercises the failure path
        // without depending on name resolution or on the public internet.
        val settings = EngineSettings(
            mode = EngineMode.REMOTE,
            apiKey = "sk-x",
            providerUrl = "https://127.0.0.1:1/analyze",
            consentedAt = 1L
        )
        val service = service(settings)
        val state = service.build(capsule(DemoConversations.A))
        assertEquals("任务完成进度", state.topic)

        val actions = service.recommend(state)
        assertEquals("recommend must fall back too", 3, actions.size)
        assertEquals(listOf(1, 2, 3), actions.map { it.priority })

        val result = service.execute(capsule(DemoConversations.A), state, actions.first())
        assertTrue("execute must fall back too", result.replies.isNotEmpty())
    }

    @Test
    fun `changing the provider address requires a fresh agreement`() {
        // Mirrors the store's rule: consent is tied to a destination, not to the mode.
        val agreed = EngineSettings(mode = EngineMode.REMOTE, apiKey = "sk-x", providerUrl = "https://a.example", consentedAt = 42L)
        assertTrue(agreed.hasConsent)
        val repointed = EngineSettings(mode = EngineMode.REMOTE, apiKey = "sk-x", providerUrl = "https://b.example", consentedAt = 0L)
        assertFalse("a new destination needs a fresh agreement", repointed.hasConsent)
    }
}
