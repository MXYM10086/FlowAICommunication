package com.flowai.communication.ui.card

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一个风格区块的展示状态。
 *
 * [Idle] 是"还没轮到"：三路回复按温暖→毒舌→冷静科学**串行**生成，轮到谁谁转 Loading。
 * [Error] 携带给用户看的友好原因（超时 / 401 / 限流 / 网络异常），异常对象不越过 UI 边界。
 */
sealed interface ReplyBlockStatus {
    /** 排队中：串行生成时位于当前请求之后的区块。 */
    data object Idle : ReplyBlockStatus

    /** 该风格的 DeepSeek 请求正在进行。 */
    data object Loading : ReplyBlockStatus

    /** 已拿到完整回复文本；打字机动画由 UI 层负责。 */
    data class Done(val text: String) : ReplyBlockStatus

    /** 该路失败；[message] 是可直接展示的中文原因。 */
    data class Error(val message: String) : ReplyBlockStatus
}

/**
 * 单个风格区块的 UI 状态。
 *
 * 普通类 + `mutableStateOf` 字段（而非不可变 data class）：串行生成与打字机推进时只更新
 * 单个区块的状态，三个区块的列表身份不变，重组范围最小。
 */
class StyleReplyUi(val styleName: String, initial: ReplyBlockStatus = ReplyBlockStatus.Idle) {
    var status by mutableStateOf(initial)
}

/**
 * 回复卡片的全部状态。仿 [com.flowai.communication.ui.panel.AssistantPanelState] 的模式。
 *
 * 卡片的 View 会在最小化/恢复、截屏挂起时**重建**（re-attach 已 detach 的 View 会失去
 * 触摸响应——面板在真机上踩过的坑），所以状态必须活在组合之外：就放这里，随窗口对象存活。
 */
class ReplyCardState(
    ocrText: String = "",
    styleNames: List<String> = DEFAULT_STYLE_NAMES
) {
    /** OCR 识别出的原文，展示在顶部可折叠区；截断到 [MAX_SOURCE_CHARS]。 */
    var ocrText by mutableStateOf(ocrText)

    /** 原文区是否折叠。默认折叠，把首屏留给三条回复。 */
    var sourceCollapsed by mutableStateOf(true)

    /** 卡片级错误（如截屏 / OCR 失败），显示在折叠区与回复区块之间。 */
    var cardError by mutableStateOf<String?>(null)

    /** 生成进度文案（如"正在生成 2/3…"）；null 表示当前没有在生成。 */
    var progress by mutableStateOf<String?>(null)

    /** 三个风格区块，顺序固定：温暖版、毒舌版、冷静科学版。 */
    val replies: List<StyleReplyUi> = styleNames.map { StyleReplyUi(it) }

    companion object {
        /** 规格约定的固定展示顺序。 */
        val DEFAULT_STYLE_NAMES = listOf("温暖版", "毒舌版", "冷静科学版")

        /** 原文区最多展示的字符数；完整原文可达 20k，全部渲染会把卡片撑爆。 */
        const val MAX_SOURCE_CHARS = 600
    }
}
