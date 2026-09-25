package com.flowai.communication.ai.deepseek

import com.flowai.communication.ai.AiStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 串行三风格生成（阶段 4）：温暖 → 毒舌 → 冷静科学，一次一路。
 *
 * 串行而非并发是规格要求，也对限流更友好：三路同时打过去比连着打更容易吃 429。
 * 某一路失败**不拖死后续**——该区块显示友好原因，其余风格照常生成。
 *
 * 取消契约：本类只运行在调用方协程里（卡片的 `lifecycleScope`）。每轮循环开头
 * [ensureActive]：一旦取消（关卡片 / 服务销毁），下一路的状态回调与请求都不再发出；
 * 在途请求由 Retrofit suspend 的取消传播中断（OkHttp `call.cancel`）。
 *
 * 网络行为以 [generate] 注入（生产为 `DeepSeekClient::generate`），JVM 单测因此能用
 * 假函数验证顺序 / 容错 / 取消，不碰网络。
 */
class StyleReplyGenerator(
    private val generate: suspend (AiStyle, String) -> DeepSeekResult
) {

    /**
     * 按 [AiStyle.ALL] 固定顺序串行请求三个风格。
     *
     * @param ocrText OCR 原文纯文本（内部截断到 [MAX_OCR_CHARS]）；只有文字离开手机。
     * @param onStarted 某风格请求发出前回调（卡片据此置 Loading 与"生成 n/3"进度）。
     * @param onFinished 某风格请求结束回调（成功或失败都走这里，卡片写区块状态）。
     */
    suspend fun generateAll(
        ocrText: String,
        onStarted: (AiStyle) -> Unit,
        onFinished: (AiStyle, DeepSeekResult) -> Unit
    ) {
        for (style in AiStyle.ALL) {
            // 已取消：立即整体退出，不发下一路请求（mock 日志据此验证"后续不再发出"）。
            currentCoroutineContext().ensureActive()
            onStarted(style)
            onFinished(style, generateOne(style, ocrText))
        }
    }

    /**
     * 单风格生成：串行流程与卡片「重试这一条」共用。
     *
     * [DeepSeekClient.generate] 已把全部异常收敛成失败对象；这里的 catch 是第二道保险——
     * 任何没料到的异常都不许越过生成边界把卡片弄崩。取消异常原样重抛：
     * 它是中断在途请求的唯一通道，被吞掉就等于关卡片取消不了请求。
     */
    suspend fun generateOne(style: AiStyle, ocrText: String): DeepSeekResult =
        try {
            generate(style, buildUserMessage(ocrText))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DeepSeekResult.Failed(DeepSeekFailure.Unexpected)
        }

    companion object {
        /** 与客户端侧上限同一口径：超长原文会把串行三路拖得极慢。 */
        internal const val MAX_OCR_CHARS = 6_000

        /**
         * 用户消息 = 简短指令 + OCR 原文。指令让人设 prompt 与这份文字的关系自明
         * （system 提示要求"阅读用户给出的聊天记录"）；离开手机的只有这段文字。
         */
        internal fun buildUserMessage(ocrText: String): String =
            "以下是截屏识别出的聊天记录文字，请站在我的一方替我写一条可以直接发送的回复：\n" +
                ocrText.take(MAX_OCR_CHARS)
    }
}
