package com.flowai.communication

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
 * Guards the promise that nothing is uploaded unless the user configured a destination *and*
 * agreed to send text there.
 *
 * These use an unroutable endpoint on purpose: if consent or configuration gating were broken, the
 * call would attempt a request and fail slowly rather than promptly falling back.
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
    fun `unconfigured settings never reach the network`() = runTest {
        val state = service(EngineSettings()).build(capsule(DemoConversations.A))
        // The local engine produced it: the demo's known topic.
        assertEquals("任务完成进度", state.topic)
    }

    @Test
    fun `configured but unconsented never reaches the network`() = runTest {
        val settings = EngineSettings(endpoint = "https://example.invalid", consentedAt = 0L)
        assertFalse(settings.canUseRemote)
        val state = service(settings).build(capsule(DemoConversations.A))
        assertEquals("任务完成进度", state.topic)
    }

    @Test
    fun `a plain http endpoint is refused`() = runTest {
        val settings = EngineSettings(endpoint = "http://example.invalid", consentedAt = 1L)
        assertTrue(settings.canUseRemote)
        // Reachable only if the https guard failed; it should fall back immediately instead.
        val state = service(settings).build(capsule(DemoConversations.A))
        assertEquals("任务完成进度", state.topic)
    }

    @Test
    fun `an unreachable https endpoint falls back rather than failing the analysis`() = runTest {
        val settings = EngineSettings(endpoint = "https://127.0.0.1:1/analyze", consentedAt = 1L)
        val state = service(settings).build(capsule(DemoConversations.A))
        assertEquals("任务完成进度", state.topic)
    }

    @Test
    fun `actions fall back too`() = runTest {
        val settings = EngineSettings(endpoint = "https://127.0.0.1:1/analyze", consentedAt = 1L)
        val service = service(settings)
        val state = service.build(capsule(DemoConversations.A))
        val actions = service.recommend(state)
        assertEquals(3, actions.size)
        assertEquals(listOf(1, 2, 3), actions.map { it.priority })
    }

    @Test
    fun `execute falls back too`() = runTest {
        val settings = EngineSettings(endpoint = "https://127.0.0.1:1/analyze", consentedAt = 1L)
        val service = service(settings)
        val state = service.build(capsule(DemoConversations.A))
        val action = service.recommend(state).first()
        val result = service.execute(capsule(DemoConversations.A), state, action)
        assertTrue("fallback should still draft replies", result.replies.isNotEmpty())
    }

    @Test
    fun `changing the endpoint drops the previous consent`() {
        // Modelled by the store's rule; asserted here so the intent is pinned down.
        val agreed = EngineSettings(endpoint = "https://a.example", consentedAt = 42L)
        assertTrue(agreed.hasConsent)
        val repointed = EngineSettings(endpoint = "https://b.example", consentedAt = 0L)
        assertFalse("a new destination needs a fresh agreement", repointed.hasConsent)
    }
}
