package com.flowai.communication.ui.card

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡片拖拽数学的单测：边界夹取（"不能拖出屏幕外"）与松手吸边目标。
 *
 * 像素口径取典型设备：1080x2400 @420dpi（density 2.625），12dp 边距 ≈ 32px，
 * 卡片宽 = 屏宽 80% = 864px。
 */
class CardDragMathTest {

    private val screenW = 1080
    private val screenH = 2400
    private val cardW = 864
    private val cardH = 1200
    private val margin = 32

    @Test
    fun clampX_keepsPositionInsideMargins() {
        assertEquals(100, CardDragMath.clampX(100, cardW, screenW, margin))
        assertEquals(margin, CardDragMath.clampX(-500, cardW, screenW, margin))
        // 右上限 = 1080 - 864 - 32 = 184
        assertEquals(184, CardDragMath.clampX(5000, cardW, screenW, margin))
    }

    @Test
    fun clampX_cardWiderThanScreen_sticksToLeftMargin() {
        // 退化输入不抛异常：上限塌缩到 margin 本身。
        assertEquals(margin, CardDragMath.clampX(999, 2000, screenW, margin))
        assertEquals(margin, CardDragMath.clampX(-999, 2000, screenW, margin))
    }

    @Test
    fun clampY_keepsCardOnScreen() {
        assertEquals(0, CardDragMath.clampY(-300, cardH, screenH, margin))
        assertEquals(800, CardDragMath.clampY(800, cardH, screenH, margin))
        // 底部上限 = 2400 - 1200 - 32 = 1168
        assertEquals(1168, CardDragMath.clampY(5000, cardH, screenH, margin))
    }

    @Test
    fun clampY_cardTallerThanScreen_sticksToTop() {
        assertEquals(0, CardDragMath.clampY(700, 3000, screenH, margin))
        assertEquals(0, CardDragMath.clampY(-700, 3000, screenH, margin))
    }

    @Test
    fun snapTargetX_picksNearerEdgeByCardCenter() {
        // 卡片中心 = x + 432。x=32 → 中心 464 < 540 → 吸左缘。
        assertEquals(margin, CardDragMath.snapTargetX(32, cardW, screenW, margin))
        // x=150 → 中心 582 > 540 → 吸右缘 = 1080-864-32 = 184。
        assertEquals(184, CardDragMath.snapTargetX(150, cardW, screenW, margin))
    }

    @Test
    fun snapTargetX_centerExactlyOnLine_snapsLeft() {
        // 中心恰好压在屏幕上：严格大于不成立 → 吸左缘（与桌宠 clampToScreen 的 > 口径一致）。
        assertEquals(margin, CardDragMath.snapTargetX(108, cardW, screenW, margin))
    }

    @Test
    fun snapTargetX_cardWiderThanScreen_degradesToLeftMargin() {
        // 退化输入：右缘目标塌缩回 margin，与 clampX 口径一致，不会来回打架。
        assertEquals(margin, CardDragMath.snapTargetX(0, 2000, screenW, margin))
        assertEquals(margin, CardDragMath.snapTargetX(-100, 2000, screenW, margin))
    }
}
