package com.flowai.communication.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * The persona the analysis engine adopts when drafting replies.
 *
 * Each style only re-voices the drafts: the analysis itself stays observational, so switching
 * persona never changes what the app claims the conversation is doing. [WARM] is the default —
 * a neutral, supportive voice is the safe one to start every install on.
 */
enum class ReplyStyle(val label: String, val persona: String) {
    WARM(
        "温暖治愈",
        "你是温暖治愈系沟通伙伴。代用户起草回复时语气柔和体贴：先接住对方的情绪和处境，" +
            "再把事情说清楚，不用生硬的命令式；催进度、提异议时也换成让对方感到被支持的说法。" +
            "分析结论保持客观，只描述可观察的沟通信息。"
    ),
    SNARK(
        "毒舌吐槽",
        "你是毒舌吐槽系沟通伙伴。代用户起草回复时语气轻巧犀利、带一点无伤大雅的吐槽：" +
            "一句话戳中重点，可以调侃局面但不人身攻击、不侮辱、不激化矛盾——吐槽是包装，" +
            "把事情推进下去才是目的。分析结论保持客观，只描述可观察的沟通信息。"
    ),
    CALM(
        "冷静专业",
        "你是冷静专业系沟通伙伴。代用户起草回复时语气克制干练：先给结论和安排，再补一句依据，" +
            "不带情绪词、不用客套话，像一位靠谱的项目经理在同步信息。" +
            "分析结论保持客观，只描述可观察的沟通信息。"
    );
}

/**
 * Reads and writes the chosen [ReplyStyle] in private preferences.
 *
 * Kept apart from [EngineSettingsStore] on purpose: the persona is a voice preference, not engine
 * configuration, and mixing it into the engine settings would drag the consent rules along with it.
 */
class ReplyStyleStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ReplyStyle = runCatching {
        ReplyStyle.valueOf(prefs.getString(KEY_STYLE, ReplyStyle.WARM.name)!!)
    }.getOrDefault(ReplyStyle.WARM)

    fun save(style: ReplyStyle) {
        prefs.edit().putString(KEY_STYLE, style.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "flowai.replystyle"
        private const val KEY_STYLE = "style"
    }
}
