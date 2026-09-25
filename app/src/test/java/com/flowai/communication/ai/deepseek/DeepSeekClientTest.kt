package com.flowai.communication.ai.deepseek

import com.flowai.communication.ai.AiStyle
import com.flowai.communication.ai.ApiFormat
import com.flowai.communication.ai.EngineMode
import com.flowai.communication.ai.EngineSettings
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.EOFException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

/**
 * 阶段 3 网络封装的纯 JVM 单测：真 Gson 序列化、固定人设、门禁与错误映射。
 *
 * 门禁四条与映射全部不发网络：gate 在构造请求前返回，mapFailure / httpFailure 是纯函数。
 */
class DeepSeekClientTest {

    // ---- 请求体形状 ----------------------------------------------------------

    @Test
    fun requestSerializesInOpenAiCompatShape() {
        val json = Gson().toJson(
            ChatRequest(
                model = "deepseek-chat",
                messages = listOf(
                    ChatMessage("system", "你是温暖体贴的沟通助手。"),
                    ChatMessage("user", "我：在吗？")
                ),
                temperature = 0.7,
                max_tokens = 512
            )
        )
        assertTrue(json, json.contains("\"model\":\"deepseek-chat\""))
        assertTrue(json, json.contains("\"temperature\":0.7"))
        assertTrue(json, json.contains("\"max_tokens\":512"))
        assertTrue(json, json.contains("\"role\":\"system\""))
        assertTrue(json, json.contains("\"role\":\"user\""))
        // 回复卡片只发纯文本：任何 image_url 都不该出现（隐私不变式：原图绝不上传）。
        assertTrue(json, !json.contains("image_url"))
    }

    @Test
    fun responseParsesFromOpenAiCompatEnvelope() {
        val response = Gson().fromJson(
            """{"choices":[{"message":{"role":"assistant","content":"好呀～"},"finish_reason":"stop"}]}""",
            ChatResponse::class.java
        )
        assertEquals("好呀～", response.choices?.firstOrNull()?.message?.content)
    }

    @Test
    fun responseWithoutChoicesParsesToNullRatherThanThrowing() {
        val response = Gson().fromJson("""{"id":"x"}""", ChatResponse::class.java)
        assertEquals(null, response.choices)
    }

    // ---- 固定人设 ------------------------------------------------------------

    @Test
    fun threeStylesInFixedOrderWithFixedNames() {
        assertEquals(listOf("warm", "snarky", "clinical"), AiStyle.ALL.map { it.id })
        assertEquals(listOf("温暖版", "毒舌版", "冷静科学版"), AiStyle.ALL.map { it.name })
    }

    @Test
    fun everyStylePromptAsksForReplyOnlyAndIsDistinct() {
        val prompts = AiStyle.ALL.map { it.systemPrompt }
        assertEquals(3, prompts.distinct().size)
        prompts.forEach { prompt ->
            assertTrue(prompt, prompt.isNotBlank())
            assertTrue(prompt, prompt.contains("只输出回复本身"))
        }
        // 毒舌人设必须自带文明约束（规格：幽默犀利但不骂人）。
        assertTrue(AiStyle.SNARKY.systemPrompt.contains("绝不说脏话"))
    }

    // ---- 门禁：不发网络 -------------------------------------------------------

    @Test
    fun notConfiguredWhenRemoteNotConsented() = runTest {
        val client = DeepSeekClient { EngineSettings() } // 默认 LOCAL / 无同意
        val result = client.generate(AiStyle.WARM, "随便一段聊天")
        assertEquals(DeepSeekFailure.NotConfigured, (result as DeepSeekResult.Failed).failure)
    }

    @Test
    fun unsupportedFormatWhenConfiguredForAnthropic() = runTest {
        val client = DeepSeekClient {
            EngineSettings(
                mode = EngineMode.REMOTE,
                apiKey = "k",
                consentedAt = 1L,
                apiFormat = ApiFormat.ANTHROPIC
            )
        }
        val result = client.generate(AiStyle.WARM, "随便一段聊天")
        assertEquals(DeepSeekFailure.UnsupportedFormat, (result as DeepSeekResult.Failed).failure)
    }

    @Test
    fun badEndpointWhenUrlUnparsable() = runTest {
        val client = DeepSeekClient { remoteWith(url = "not a url") }
        val result = client.generate(AiStyle.WARM, "随便一段聊天")
        assertEquals(DeepSeekFailure.BadEndpoint, (result as DeepSeekResult.Failed).failure)
    }

    @Test
    fun unsafeEndpointWhenCleartextToPublicHost() = runTest {
        val client = DeepSeekClient { remoteWith(url = "http://example.com/chat/completions") }
        val result = client.generate(AiStyle.WARM, "随便一段聊天")
        assertEquals(DeepSeekFailure.UnsafeEndpoint, (result as DeepSeekResult.Failed).failure)
    }

    private fun remoteWith(url: String) = EngineSettings(
        mode = EngineMode.REMOTE,
        apiKey = "k",
        providerUrl = url,
        consentedAt = 1L
    )

    // ---- 错误映射：文案锁死 ----------------------------------------------------

    @Test
    fun httpCodesMapToFriendlyMessages() {
        assertEquals("API Key 无效或未授权", DeepSeekClient.httpFailure(401).friendlyMessage)
        assertEquals("API Key 无效或未授权", DeepSeekClient.httpFailure(403).friendlyMessage)
        assertEquals("请求过于频繁（限流），请稍后再试", DeepSeekClient.httpFailure(429).friendlyMessage)
        assertEquals("服务暂时不可用", DeepSeekClient.httpFailure(500).friendlyMessage)
        assertEquals("服务暂时不可用", DeepSeekClient.httpFailure(503).friendlyMessage)
        assertEquals("服务返回意外状态（418），请稍后再试", DeepSeekClient.httpFailure(418).friendlyMessage)
    }

    @Test
    fun exceptionsMapToFriendlyMessages() {
        assertEquals(
            "请求超时，请检查网络后重试",
            DeepSeekClient.mapFailure(SocketTimeoutException("timeout")).friendlyMessage
        )
        assertEquals(
            "网络异常，请检查网络连接",
            DeepSeekClient.mapFailure(UnknownHostException("dns")).friendlyMessage
        )
        // 200 但空响应体：Gson 读空流抛 EOF，属于格式问题而不是网络问题。
        assertEquals("响应格式异常", DeepSeekClient.mapFailure(EOFException()).friendlyMessage)
        assertEquals("响应格式异常", DeepSeekClient.mapFailure(JsonSyntaxException("bad")).friendlyMessage)
        assertEquals("生成失败，请重试", DeepSeekClient.mapFailure(RuntimeException("?!")).friendlyMessage)
    }

    @Test
    fun retrofitHttpExceptionGoesThroughCodeMapping() {
        val failure = DeepSeekClient.mapFailure(httpException(429))
        assertEquals(DeepSeekFailure.RateLimited, failure)
    }

    private fun httpException(code: Int): HttpException =
        HttpException(
            retrofit2.Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType()))
        )
}
