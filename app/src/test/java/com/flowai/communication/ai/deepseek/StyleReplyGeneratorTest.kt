package com.flowai.communication.ai.deepseek

import com.flowai.communication.ai.AiStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 串行三风格生成器单测（阶段 4）：顺序、单路容错、取消契约、用户消息形状。
 *
 * 网络行为用假函数注入，不碰网络；取消用例靠 [CompletableDeferred] 把第一路挂住，
 * 再取消 Job 验证"后续风格不再发出"。
 */
class StyleReplyGeneratorTest {

    private fun ok(text: String) = DeepSeekResult.Success(text)

    @Test
    fun generatesAllStylesInFixedOrderSerially() = runTest {
        val started = mutableListOf<String>()
        val inFlight = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val generator = StyleReplyGenerator { style, _ ->
            // 记录"在途"顺序：串行意味着进入下一路时上一路早已结束。
            inFlight += style.id
            ok(style.id)
        }
        generator.generateAll(
            ocrText = "聊天记录",
            onStarted = { started += it.id },
            onFinished = { style, result ->
                finished += style.id + (result as DeepSeekResult.Success).text
            }
        )
        assertEquals(listOf("warm", "snarky", "clinical"), started)
        assertEquals(listOf("warm", "snarky", "clinical"), inFlight)
        assertEquals(listOf("warmwarm", "snarkysnarky", "clinicalclinical"), finished)
    }

    @Test
    fun singleFailureDoesNotStopLaterStyles() = runTest {
        val finished = mutableListOf<Pair<String, Boolean>>()
        val generator = StyleReplyGenerator { style, _ ->
            if (style == AiStyle.SNARKY) DeepSeekResult.Failed(DeepSeekFailure.RateLimited)
            else ok(style.id)
        }
        generator.generateAll(
            ocrText = "聊天记录",
            onStarted = { },
            onFinished = { style, result -> finished += style.id to (result is DeepSeekResult.Success) }
        )
        assertEquals(
            listOf("warm" to true, "snarky" to false, "clinical" to true),
            finished
        )
    }

    @Test
    fun cancellationStopsBeforeNextStyleStarts() = runTest {
        val started = mutableListOf<String>()
        val called = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val generator = StyleReplyGenerator { style, _ ->
            called += style.id
            if (style == AiStyle.WARM) {
                release.await() // 第一路挂住，等外部取消
                ok("never")
            } else {
                ok(style.id)
            }
        }
        val job = launch {
            generator.generateAll("聊天记录", { started += it.id }, { _, _ -> })
        }
        runCurrent() // 让协程跑到第一路的 await
        assertEquals(listOf("warm"), started)
        assertEquals(listOf("warm"), called)
        job.cancelAndJoin()
        runCurrent()
        // 取消后第二路的状态回调与请求都不再发出（mock 日志在真机上验证同一条契约）。
        assertEquals(listOf("warm"), started)
        assertEquals(listOf("warm"), called)
    }

    @Test
    fun generateOneMapsUnexpectedExceptionToFailure() = runTest {
        val generator = StyleReplyGenerator { _, _ -> error("boom") }
        val result = generator.generateOne(AiStyle.WARM, "聊天记录")
        assertTrue(result is DeepSeekResult.Failed)
        assertEquals(DeepSeekFailure.Unexpected, (result as DeepSeekResult.Failed).failure)
    }

    @Test
    fun generateOneRethrowsCancellation() = runTest {
        val generator = StyleReplyGenerator { _, _ -> throw CancellationException("cancelled") }
        try {
            generator.generateOne(AiStyle.WARM, "聊天记录")
            fail("cancellation must propagate out of the generator")
        } catch (expected: CancellationException) {
            // 取消是唯一必须越过生成边界的异常：关卡片靠它中断在途请求。
        }
    }

    @Test
    fun userMessageCarriesInstructionAndTruncatesOcr() {
        val short = StyleReplyGenerator.buildUserMessage("聊天记录")
        assertTrue(short.startsWith("以下是截屏识别出的聊天记录文字"))
        assertTrue(short.endsWith("聊天记录"))

        val long = StyleReplyGenerator.buildUserMessage("a".repeat(6_500))
        val prefixLen = long.indexOf('\n') + 1
        // OCR 原文截断到 6000 字：指令前缀 + 6000，不多带一个字出门。
        assertEquals(prefixLen + StyleReplyGenerator.MAX_OCR_CHARS, long.length)
        assertTrue(long.endsWith("a".repeat(StyleReplyGenerator.MAX_OCR_CHARS)))
    }
}
