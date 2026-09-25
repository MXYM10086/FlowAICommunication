package com.flowai.communication.ai.deepseek

/**
 * OpenAI 兼容接口的线上实体（阶段 3 回复卡片专用）。
 *
 * 字段名即协议名（`max_tokens`、`finish_reason` 带下划线），Gson 按名字直接映射，
 * 不需要注解；响应里用不到的字段一律不声明，Gson 会忽略多余字段。
 */

/** 一条对话消息：`system` / `user` / `assistant` + 纯文本内容。 */
internal data class ChatMessage(val role: String, val content: String)

/**
 * 聊天补全请求。
 *
 * [temperature] 0.7：人设回复需要一点发挥空间（分析链路用 0.3 求稳，这里求"有味道"）；
 * [max_tokens] 512：人设提示已约束"只输出回答、一两句话"，512 足够且给耗时与费用封顶。
 */
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    val max_tokens: Int
)

/** `choices[]` 的一项；`message` 理论上可缺，按可空接住而不是让 Gson 崩在反序列化上。 */
internal data class ChatChoice(val message: ChatMessage?, val finish_reason: String?)

/** 聊天补全响应；`choices` 缺省时为 null，由客户端映射成"响应格式异常"。 */
internal data class ChatResponse(val choices: List<ChatChoice>?)
