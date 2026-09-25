package com.flowai.communication.ui.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打字机步长数学的单测：短文本逐字、长文本自适应放大、总时长封顶。
 */
class TypewriterMathTest {

    @Test
    fun shortText_stepsOneCharAtATime() {
        assertEquals(1, typewriterStep(1))
        assertEquals(1, typewriterStep(60))
        assertEquals(1, typewriterStep(TYPEWRITER_MAX_STEPS))
    }

    @Test
    fun emptyOrNegativeText_stepIsAtLeastOne() {
        assertEquals(1, typewriterStep(0))
        assertEquals(1, typewriterStep(-5))
    }

    @Test
    fun longText_stepBoundsTotalStepsToMax() {
        // 5000 字：步长 = ceil(5000/125) = 40，总步数 = ceil(5000/40) = 125 ≤ 上限。
        val step = typewriterStep(5000)
        assertEquals(40, step)
        val totalSteps = (5000 + step - 1) / step
        assertTrue("totalSteps=$totalSteps", totalSteps <= TYPEWRITER_MAX_STEPS)
    }

    @Test
    fun extremeText_totalDurationStaysCapped() {
        // 20000 字（OCR 上限口径）：动画时长仍被压在 ~maxSteps*delay，不线性膨胀。
        val step = typewriterStep(20_000)
        val totalSteps = (20_000 + step - 1) / step
        assertTrue("totalSteps=$totalSteps", totalSteps <= TYPEWRITER_MAX_STEPS)
        val durationMs = totalSteps * TYPEWRITER_STEP_DELAY_MS
        assertTrue(
            "durationMs=$durationMs",
            durationMs <= TYPEWRITER_MAX_STEPS * TYPEWRITER_STEP_DELAY_MS
        )
    }

    @Test
    fun stepNeverZero_forAnyLength() {
        // 步长为 0 会让推进循环死转；抽样验证恒 ≥ 1。
        for (len in intArrayOf(0, 1, 7, 124, 125, 126, 999, 12_345, 100_000)) {
            assertTrue("len=$len", typewriterStep(len) >= 1)
        }
    }
}
