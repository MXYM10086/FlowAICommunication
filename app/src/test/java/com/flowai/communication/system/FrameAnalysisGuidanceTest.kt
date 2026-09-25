package com.flowai.communication.system

import com.flowai.communication.domain.CaptureFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 识别失败 → 中文引导语 的映射单测。
 *
 * 面板与回复卡片共用 [FrameAnalysis.guidanceFor]，这里锁死四条文案：
 * 卡片阶段 2 的"黑帧 / 无文字"验收依赖它，改文案会在这里先红。
 */
class FrameAnalysisGuidanceTest {

    @Test
    fun blackFrame_explainsScreenCaptureProtection() {
        assertEquals(
            "截屏全黑：对方应用开启了截屏保护，请切换到能正常显示聊天的界面后重试",
            FrameAnalysis.guidanceFor(CaptureFailure.BLACK_FRAME)
        )
    }

    @Test
    fun noText_asksToReframe() {
        assertEquals(
            "没有识别到文字，请确认聊天内容完整显示后重试",
            FrameAnalysis.guidanceFor(CaptureFailure.NO_TEXT)
        )
    }

    @Test
    fun timeout_asksToRetry() {
        assertEquals("识别超时，请重试", FrameAnalysis.guidanceFor(CaptureFailure.OCR_TIMEOUT))
    }

    @Test
    fun unknownReasons_fallBackToGenericGuidance() {
        assertEquals("识别失败，请重试", FrameAnalysis.guidanceFor(CaptureFailure.UNKNOWN))
        assertEquals("识别失败，请重试", FrameAnalysis.guidanceFor(CaptureFailure.SERVICE_DEAD))
    }
}
