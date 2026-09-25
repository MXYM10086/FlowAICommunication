package com.flowai.communication.ai.deepseek

import android.util.Log
import com.flowai.communication.ai.AiStyle
import com.flowai.communication.ai.ApiFormat
import com.flowai.communication.ai.EngineSettings
import com.flowai.communication.ai.isSecureOrLocal
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** 一路风格生成的两种结局：拿到回复文本，或一个能映射成友好文案的失败。 */
sealed interface DeepSeekResult {
    data class Success(val text: String) : DeepSeekResult
    data class Failed(val failure: DeepSeekFailure) : DeepSeekResult
}

/**
 * 回复卡片网络层的全部失败形态与对用户文案。
 *
 * 任何网络 / HTTP / 解析异常都收敛到这里，**不向外抛**（规格：绝不崩溃）；
 * 协程取消除外——取消必须原样传播，否则关卡片取消不了在途请求。
 */
sealed interface DeepSeekFailure {
    /** 未切到远程模型、没填 Key、或没同意上传——门禁三件套缺一。 */
    data object NotConfigured : DeepSeekFailure

    /** 引擎设置是 Anthropic / Gemini 形态；回复卡片固定走 OpenAI 兼容接口。 */
    data object UnsupportedFormat : DeepSeekFailure

    /** 服务地址解析不了。 */
    data object BadEndpoint : DeepSeekFailure

    /** 明文且非本机 / 私网地址，被安全门禁拒绝。 */
    data object UnsafeEndpoint : DeepSeekFailure

    /** 401 / 403。 */
    data object AuthDenied : DeepSeekFailure

    /** 429。 */
    data object RateLimited : DeepSeekFailure

    /** 5xx：对方服务自己的问题，让用户稍后再试。 */
    data object ServerError : DeepSeekFailure

    /** 其余 HTTP 状态码：带码上报，便于真机排查。 */
    data class UnexpectedStatus(val code: Int) : DeepSeekFailure

    /** 连接 / 读取 / 整体调用超时。 */
    data object Timeout : DeepSeekFailure

    /** 断网、DNS 失败、连接被拒等网络层异常。 */
    data object Network : DeepSeekFailure

    /** 200 但响应体不是约定形状、choices 为空、或空响应体。 */
    data object BadPayload : DeepSeekFailure

    /** 兜底：没料到的异常也有话说，绝不把异常抛给用户界面。 */
    data object Unexpected : DeepSeekFailure

    /** 卡片区块里直接展示的友好文案。 */
    val friendlyMessage: String
        get() = when (this) {
            NotConfigured -> "未配置远程模型或未同意上传，请在引擎设置中检查"
            UnsupportedFormat -> "回复卡片仅支持 OpenAI 兼容接口，请在引擎设置中切换"
            BadEndpoint -> "服务地址无效，请在引擎设置中检查"
            UnsafeEndpoint -> "仅支持 https 或本机调试地址，请在引擎设置中检查"
            AuthDenied -> "API Key 无效或未授权"
            RateLimited -> "请求过于频繁（限流），请稍后再试"
            ServerError -> "服务暂时不可用"
            is UnexpectedStatus -> "服务返回意外状态（$code），请稍后再试"
            Timeout -> "请求超时，请检查网络后重试"
            Network -> "网络异常，请检查网络连接"
            BadPayload -> "响应格式异常"
            Unexpected -> "生成失败，请重试"
        }
}

/**
 * 三风格回复卡片的网络客户端（阶段 3）。
 *
 * 与 [com.flowai.communication.ai.RemoteLlmService] 的关系：那条是分析流水线（裸
 * HttpURLConnection + org.json，三协议），本类只服务回复卡片的"单人设单轮补全"，
 * 用 Retrofit + OkHttp + Gson 独立实现；**Key / 地址 / 上传同意门禁复用
 * [EngineSettings]**，每次调用读最新设置，设置页改完立即生效。
 *
 * 安全不变式：
 * - 门禁 [EngineSettings.canUseRemote] 为假直接返回 [DeepSeekFailure.NotConfigured]，不碰网络；
 * - 只接受 [ApiFormat.OPENAI_COMPAT]（卡片流程固定 DeepSeek 形态）；
 * - 明文端点复用 [isSecureOrLocal]：https 或 DEBUG 下的回环 / 私网；
 * - 日志只记失败形态与状态码，**绝不记 Authorization 或 Key**。
 */
class DeepSeekClient(private val settingsProvider: () -> EngineSettings) {

    /**
     * 以 [style] 的人设对 [userText]（OCR 原文等纯文本）求一条回复。
     *
     *  suspend：随调用方协程取消而取消（Retrofit suspend → OkHttp call.cancel）。
     */
    suspend fun generate(style: AiStyle, userText: String): DeepSeekResult =
        withContext(Dispatchers.IO) {
            val settings = settingsProvider()
            if (!settings.canUseRemote) return@withContext failed(DeepSeekFailure.NotConfigured)
            if (settings.apiFormat != ApiFormat.OPENAI_COMPAT) {
                return@withContext failed(DeepSeekFailure.UnsupportedFormat)
            }
            val endpoint = runCatching { URL(settings.providerUrl.trim()) }
                .getOrElse { return@withContext failed(DeepSeekFailure.BadEndpoint) }
            if (!endpoint.isSecureOrLocal()) {
                Log.w(TAG, "reply card refusing cleartext endpoint: ${endpoint.protocol}")
                return@withContext failed(DeepSeekFailure.UnsafeEndpoint)
            }
            val request = ChatRequest(
                model = settings.model,
                messages = listOf(
                    ChatMessage(ROLE_SYSTEM, style.systemPrompt),
                    // 与现有分析链路的 grounding 上限同一口径，避免超长原文拖死串行三路。
                    ChatMessage(ROLE_USER, userText.take(MAX_USER_CHARS))
                ),
                temperature = TEMPERATURE,
                max_tokens = MAX_TOKENS
            )
            try {
                val response = api.chat(endpoint.toString(), "Bearer ${settings.apiKey}", request)
                val text = response.choices?.firstOrNull()?.message?.content?.trim().orEmpty()
                if (text.isEmpty()) failed(DeepSeekFailure.BadPayload)
                else {
                    Log.i(TAG, "deepseek ok style=${style.id} chars=${text.length}")
                    DeepSeekResult.Success(text)
                }
            } catch (e: CancellationException) {
                throw e // 取消原样传播：关卡片 / 服务销毁时在途请求靠它中断。
            } catch (e: Exception) {
                failed(mapFailure(e))
            }
        }

    private fun failed(failure: DeepSeekFailure): DeepSeekResult.Failed {
        Log.w(TAG, "deepseek failed: ${failure::class.java.simpleName}")
        return DeepSeekResult.Failed(failure)
    }

    companion object {
        private const val TAG = "FlowAI"

        // 与 RemoteLlmService 同一口径：连接 10s / 读取 60s，另加 75s 整体封顶，
        // 避免连接活着但永不返回的请求挂住串行队列。
        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 60L
        private const val CALL_TIMEOUT_S = 75L

        /** 人设回复求"有味道"（分析链路用 0.3 求稳）；取值经用户确认。 */
        private const val TEMPERATURE = 0.7

        /** "只输出回答、一两句话"的回复，512 token 足够并给耗时封顶；取值经用户确认。 */
        private const val MAX_TOKENS = 512

        private const val MAX_USER_CHARS = 6_000
        private const val ROLE_SYSTEM = "system"
        private const val ROLE_USER = "user"

        /** baseUrl 仅占位（@Url 逐请求传真实端点），Retrofit 要求合法 HttpUrl。 */
        private const val PLACEHOLDER_BASE_URL = "https://placeholder.invalid/"

        /**
         * 全局共享的 Retrofit 实例：超时是常量、端点与 Key 逐请求传入，
         * 因此不需要随设置重建；连接池也在串行三路之间复用。
         */
        private val api: DeepSeekApi by lazy {
            Retrofit.Builder()
                .baseUrl(PLACEHOLDER_BASE_URL)
                .client(
                    OkHttpClient.Builder()
                        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
                        .build()
                )
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(DeepSeekApi::class.java)
        }

        /**
         * 异常 → 失败形态。顺序有讲究：[SocketTimeoutException] 与 [EOFException]
         * 都是 [IOException] 的子类，必须先于网络异常判断——前者是超时，
         * 后者是"200 但空响应体"（Gson 读空流抛 EOF），属于响应格式问题。
         */
        internal fun mapFailure(e: Throwable): DeepSeekFailure = when {
            e is HttpException -> httpFailure(e.code())
            e is SocketTimeoutException -> DeepSeekFailure.Timeout
            e is EOFException -> DeepSeekFailure.BadPayload
            e is IOException -> DeepSeekFailure.Network
            e is com.google.gson.JsonParseException -> DeepSeekFailure.BadPayload
            else -> DeepSeekFailure.Unexpected
        }

        internal fun httpFailure(code: Int): DeepSeekFailure = when (code) {
            401, 403 -> DeepSeekFailure.AuthDenied
            429 -> DeepSeekFailure.RateLimited
            in 500..599 -> DeepSeekFailure.ServerError
            else -> DeepSeekFailure.UnexpectedStatus(code)
        }
    }
}
