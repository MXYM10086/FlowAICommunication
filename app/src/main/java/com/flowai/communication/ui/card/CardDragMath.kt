package com.flowai.communication.ui.card

/**
 * 卡片拖拽的纯数学：边界夹取与松手吸边的目标计算。
 *
 * 刻意不依赖任何 Android 类型，因此可以在 JVM 单测里直接验证。坐标系与
 * `WindowManager.LayoutParams.x/y` 一致（屏幕左上角为原点）；卡片窗口**不加**
 * `FLAG_LAYOUT_NO_LIMITS`，系统已把窗口可用区收进状态栏 / 导航栏 / 挖孔安全区内，
 * 这里只需处理水平边距与"卡片不沉出屏幕底部"。
 *
 * 所有函数都容忍退化输入（卡片比屏幕宽 / 高时贴住起点边），不抛异常。
 */
internal object CardDragMath {

    /** 水平夹取：卡片左右都至少保留 [margin]，不会拖出屏幕。 */
    fun clampX(x: Int, cardWidth: Int, screenWidth: Int, margin: Int): Int =
        x.coerceIn(margin, maxOf(margin, screenWidth - cardWidth - margin))

    /** 垂直夹取：顶部不出安全区起点（y≥0），底部保留 [margin] 便于够到最后一行。 */
    fun clampY(y: Int, cardHeight: Int, screenHeight: Int, margin: Int): Int =
        y.coerceIn(0, maxOf(0, screenHeight - cardHeight - margin))

    /**
     * 松手吸边的目标 x：按卡片**中心**落在屏幕哪半边，吸到左缘或右缘（保留 [margin]）。
     * 中心恰好压线时吸左边（严格大于比较），与桌宠
     * [com.flowai.communication.system.FloatingAssistantService] `clampToScreen` 的口径一致。
     */
    fun snapTargetX(x: Int, cardWidth: Int, screenWidth: Int, margin: Int): Int {
        val center = x + cardWidth / 2
        return if (center > screenWidth / 2) maxOf(margin, screenWidth - cardWidth - margin) else margin
    }
}
