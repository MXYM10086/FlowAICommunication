package com.flowai.communication.ai.deepseek

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * OpenAI 兼容接口的最小契约：一次 POST，一个补全。
 *
 * 完整端点经 [@Url] 逐请求传入：`EngineSettings.providerUrl` 本来就是完整的
 * `/chat/completions` 地址（DeepSeek / 千问 / GLM / Moonshot / Ollama 同形），
 * Retrofit 的 baseUrl 只占位。suspend 函数随协程取消自动 cancel 底层 OkHttp call——
 * 阶段 4 的"关卡片即取消"靠的就是这条传播链。
 */
internal interface DeepSeekApi {
    @POST
    suspend fun chat(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): ChatResponse
}
