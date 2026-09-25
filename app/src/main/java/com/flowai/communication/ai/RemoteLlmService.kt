package com.flowai.communication.ai

import android.util.Log
import com.flowai.communication.data.model.*
import com.flowai.communication.domain.ChatRole
import com.flowai.communication.domain.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Calls the model API and turns its answer into the shape the UI renders.
 *
 * **One request returns the whole analysis.** The engine interface has three methods because the UI
 * reveals information in stages, but three round trips would mean three waits during a demo and
 * three chances to time out. The first call fetches state, actions *and* draft replies together;
 * the later two are served from that result.
 *
 * The key comes from settings, entered by the user. Any failure falls back to [MockLlmService], so
 * a share, selection or capture never dead-ends because a network call went wrong.
 */
class RemoteLlmService(
    private val settingsProvider: () -> EngineSettings,
    /** Persona whose setting opens the system prompt; read per request so a switch applies at once. */
    private val styleProvider: () -> ReplyStyle = { ReplyStyle.WARM },
    private val fallback: LlmService = MockLlmService()
) : LlmService {

    /** The single response, kept for the follow-up calls the UI makes. */
    private var cached: Analysis? = null
    private var cachedFor: String? = null

    override suspend fun build(context: ContextCapsule): ConversationState {
        cached = null
        cachedFor = null
        val response = complete(
            settingsProvider(),
            systemPrompt(styleProvider()),
            analysisMessages(context),
            jsonObject = true
        )
        val analysis = response?.let { raw ->
            runCatching { parse(JSONObject(stripCodeFence(raw))) }
                .onFailure { e -> Log.w(TAG, "analysis response rejected, using local engine", e) }
                .getOrNull()
        }
        if (analysis == null) {
            // The local fallback reads text; it cannot look at a picture. A screenshot request
            // that fails has nothing to degrade to, so surface the failure instead of throwing
            // a confusing "no messages" error from the mock engine.
            if (context.imageBase64 != null) {
                throw IllegalStateException("读图分析失败：请检查网络，或确认所配模型支持图片输入")
            }
            return fallback.build(context)
        }
        cached = analysis
        cachedFor = cacheKey(context)
        lastKey = cachedFor
        return analysis.state
    }

    override suspend fun recommend(state: ConversationState): List<NextAction> {
        val cachedActions = cached?.actions.orEmpty()
        Log.i(
            TAG,
            "recommend: cached=${cached != null} actions=${cachedActions.size} " +
                "sameText=${cachedFor == lastKey}"
        )
        if (cachedActions.isNotEmpty()) return cachedActions
        return fallback.recommend(state)
    }

    override suspend fun execute(
        context: ContextCapsule,
        state: ConversationState,
        action: NextAction
    ): ActionResult {
        val replies = cached
            ?.takeIf { cachedFor == cacheKey(context) }
            ?.repliesByAction
            ?.get(action.id)
            .orEmpty()
        if (replies.isEmpty()) return fallback.execute(context, state, action)
        return ActionResult(replies = replies, note = cached?.note.orEmpty())
    }

    /**
     * The follow-up conversation over one finished analysis.
     *
     * The model gets the analysis it produced plus the original payload as grounding, then the
     * turns so far — so questions about the analysis are answered in context rather than from a
     * blank slate. Plain prose back, no JSON contract: this is a conversation, not a render.
     */
    override suspend fun chat(
        context: ContextCapsule,
        state: ConversationState,
        history: List<ChatTurn>,
        question: String
    ): String {
        val settings = settingsProvider()
        if (!settings.isConfigured) return fallback.chat(context, state, history, question)
        val reply = complete(
            settings,
            chatSystem(styleProvider(), context, state),
            chatMessages(history, question),
            jsonObject = false
        )
        if (reply.isNullOrBlank()) return fallback.chat(context, state, history, question)
        return reply.trim()
    }

    // ---------------------------------------------------------------- request

    /** One round trip in whatever protocol the endpoint speaks; the reply text, or null. */
    private suspend fun complete(
        settings: EngineSettings,
        system: String,
        messages: List<WireMessage>,
        jsonObject: Boolean
    ): String? {
        if (!settings.isConfigured) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(endpointFor(settings))
                if (!url.isSecureOrLocal()) {
                    Log.w(TAG, "refusing cleartext endpoint: ${url.protocol}")
                    return@runCatching null
                }
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TimeUnit.SECONDS.toMillis(CONNECT_TIMEOUT_S).toInt()
                    readTimeout = TimeUnit.SECONDS.toMillis(READ_TIMEOUT_S).toInt()
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    authHeaders(settings).forEach { (name, value) -> setRequestProperty(name, value) }
                }
                try {
                    val body = requestBody(settings, system, messages, jsonObject)
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        val detail = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                        Log.w(TAG, "engine returned HTTP $code: ${detail?.take(300)}")
                        return@runCatching null
                    }
                    val raw = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                    responseText(settings, raw)
                        .also { Log.i(TAG, "engine ok via ${settings.apiFormat}, chars=${it.length}") }
                } finally {
                    connection.disconnect()
                }
            }.onFailure { Log.w(TAG, "engine request failed", it) }.getOrNull()
        }
    }

    /**
     * Where the request goes. OpenAI-compatible providers publish the full completions URL;
     * Anthropic and Gemini publish a base and derive the path (Gemini's even carries the model
     * and the key), so a pasted base URL works for all three.
     */
    private fun endpointFor(settings: EngineSettings): String {
        val base = settings.providerUrl.trimEnd('/')
        return when (settings.apiFormat) {
            ApiFormat.OPENAI_COMPAT -> base
            ApiFormat.ANTHROPIC -> if (base.endsWith("/v1/messages")) base else "$base/v1/messages"
            ApiFormat.GEMINI ->
                "$base/models/${settings.model}:generateContent" +
                    "?key=${java.net.URLEncoder.encode(settings.apiKey, "UTF-8")}"
        }
    }

    /** Each protocol authenticates differently; Gemini takes the key in the query instead. */
    private fun authHeaders(settings: EngineSettings): List<Pair<String, String>> {
        val key = settings.apiKey
        if (key.isBlank()) return emptyList()
        return when (settings.apiFormat) {
            ApiFormat.OPENAI_COMPAT -> listOf("Authorization" to "Bearer $key")
            ApiFormat.ANTHROPIC -> listOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
            ApiFormat.GEMINI -> emptyList()
        }
    }

    /** The request body in the configured protocol's shape. */
    private fun requestBody(
        settings: EngineSettings,
        system: String,
        messages: List<WireMessage>,
        jsonObject: Boolean
    ): JSONObject = when (settings.apiFormat) {
        ApiFormat.OPENAI_COMPAT -> JSONObject()
            .put("model", settings.model)
            .put("temperature", 0.3)
            // Only the analysis needs the strict-object ask; chat wants prose back.
            .apply { if (jsonObject) put("response_format", JSONObject().put("type", "json_object")) }
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .apply {
                        messages.forEach { m ->
                            put(JSONObject().put("role", m.role.wire).put("content", content(m, ::openAiPart)))
                        }
                    }
            )
        ApiFormat.ANTHROPIC -> JSONObject()
            .put("model", settings.model)
            .put("max_tokens", 2048)
            .put("system", system)
            .put(
                "messages",
                JSONArray().apply {
                    messages.forEach { m ->
                        put(JSONObject().put("role", m.role.wire).put("content", content(m, ::anthropicPart)))
                    }
                }
            )
        ApiFormat.GEMINI -> JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents",
                JSONArray().apply {
                    messages.forEach { m ->
                        put(
                            JSONObject().put("role", if (m.role == WireMessage.Role.USER) "user" else "model").put(
                                "parts",
                                JSONArray().apply { m.parts.forEach { put(geminiPart(it)) } }
                            )
                        )
                    }
                }
            )
    }

    /**
     * A message body: a lone text part stays a plain string (what every protocol expects for
     * ordinary turns); anything multimodal becomes a part array through [encode].
     */
    private fun content(message: WireMessage, encode: (Part) -> JSONObject): Any =
        if (message.parts.size == 1 && message.parts[0] is Part.Text) {
            (message.parts[0] as Part.Text).text
        } else {
            JSONArray().apply { message.parts.forEach { put(encode(it)) } }
        }

    private fun openAiPart(part: Part): JSONObject = when (part) {
        is Part.Text -> JSONObject().put("type", "text").put("text", part.text)
        is Part.Image -> JSONObject().put("type", "image_url").put(
            "image_url", JSONObject().put("url", "data:image/png;base64,${part.base64}")
        )
    }

    private fun anthropicPart(part: Part): JSONObject = when (part) {
        is Part.Text -> JSONObject().put("type", "text").put("text", part.text)
        is Part.Image -> JSONObject().put("type", "image").put(
            "source",
            JSONObject().put("type", "base64").put("media_type", "image/png").put("data", part.base64)
        )
    }

    private fun geminiPart(part: Part): JSONObject = when (part) {
        is Part.Text -> JSONObject().put("text", part.text)
        is Part.Image -> JSONObject().put(
            "inlineData", JSONObject().put("mimeType", "image/png").put("data", part.base64)
        )
    }

    /** The reply text out of each protocol's response envelope. */
    private fun responseText(settings: EngineSettings, raw: String): String {
        val json = JSONObject(raw)
        return when (settings.apiFormat) {
            ApiFormat.OPENAI_COMPAT -> json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            ApiFormat.ANTHROPIC -> json.optJSONArray("content").toPartTexts()
            ApiFormat.GEMINI -> json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                .toPartTexts()
        }
    }

    /** Concatenated `text` parts; both Anthropic and Gemini answer in part arrays. */
    private fun JSONArray?.toPartTexts(): String {
        if (this == null) return ""
        return (0 until length()).joinToString("") { i -> optJSONObject(i)?.optString("text").orEmpty() }
    }

    /**
     * The conversation as wire messages: one user turn, plain text or — for a screenshot — the
     * reading instruction plus the image part, which each protocol then encodes its own way.
     */
    private fun analysisMessages(context: ContextCapsule): List<WireMessage> {
        val image = context.imageBase64
        return listOf(
            if (image == null) {
                WireMessage(WireMessage.Role.USER, listOf(Part.Text(context.rawText.orEmpty())))
            } else {
                WireMessage(
                    WireMessage.Role.USER,
                    listOf(Part.Text(IMAGE_INSTRUCTION), Part.Image(image))
                )
            }
        )
    }

    private fun chatMessages(history: List<ChatTurn>, question: String): List<WireMessage> =
        history.map { turn ->
            WireMessage(
                if (turn.role == ChatRole.USER) WireMessage.Role.USER else WireMessage.Role.ASSISTANT,
                listOf(Part.Text(turn.text))
            )
        } + WireMessage(WireMessage.Role.USER, listOf(Part.Text(question)))

    /**
     * Grounding for the follow-up conversation: the analysis the model itself produced, plus the
     * original payload (or a note that it was a screenshot), so追问 has something to stand on.
     */
    private fun chatSystem(style: ReplyStyle, context: ContextCapsule, state: ConversationState): String {
        val source = context.rawText?.take(MAX_GROUNDING_CHARS)
            ?: "（原始输入是一张截屏图片，对话内容以分析结论为准）"
        return buildString {
            append(style.persona).append("\n\n")
            append("你正在就和用户已完成的一次沟通分析进行追问对话。结合下面的分析结论与原始内容回答，")
            append("像可靠的同事一样直接给答案；信息不足就说不确定，不要编造。输出纯文本，不要 JSON。\n\n")
            append("分析结论：主题=").append(state.topic)
            append("；阶段=").append(state.stage)
            append("；未解决问题=").append(state.unresolvedIssues.joinToString("、"))
            append("；关键事实=").append(state.keyFacts.joinToString("、"))
            append("；沟通信号=").append(state.communicationSignals.joinToString("、"))
            append("\n原始内容：\n").append(source)
        }
    }

    /** One protocol-agnostic message: a role plus text and/or image parts. */
    private data class WireMessage(val role: Role, val parts: List<Part>) {
        enum class Role(val wire: String) { USER("user"), ASSISTANT("assistant") }
    }

    private sealed interface Part {
        data class Text(val text: String) : Part
        data class Image(val base64: String) : Part
    }

    /**
     * Identity of the payload the cached analysis belongs to. Images have no raw text, so their
     * key is derived from the image itself; either way a later call with a different payload
     * cannot read a stale result.
     */
    private fun cacheKey(context: ContextCapsule): String =
        context.imageBase64?.let { "image:${it.hashCode()}" } ?: context.rawText.orEmpty()

    /**
     * The system message for one request: the chosen persona's setting first, then the schema
     * contract. The persona replaces the voice the model drafts in; [INSTRUCTION] stays because it
     * asks for exactly the JSON shape the UI renders — a persona alone would ramble.
     */
    private fun systemPrompt(style: ReplyStyle): String = "${style.persona}\n\n$INSTRUCTION"

    /** Providers sometimes wrap JSON in a code fence despite being asked for an object. */
    private fun stripCodeFence(raw: String): String {
        val trimmed = raw.trim()
        val withoutOpen = trimmed.replaceFirst(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
        return withoutOpen.replaceFirst(Regex("```\\s*$"), "").trim()
    }

    // ---------------------------------------------------------------- parsing

    private class Analysis(
        val state: ConversationState,
        val actions: List<NextAction>,
        val repliesByAction: Map<String, List<ReplyCandidate>>,
        val note: String
    )

    /** The payload key the cached analysis belongs to, so a later call cannot read a stale result. */
    private var lastKey: String? = null

    private fun parse(json: JSONObject): Analysis {
        val actions = json.optJSONArray("actions").toActions()
        val replies = json.optJSONObject("replies")
        return Analysis(
            state = ConversationState(
                topic = json.optString("topic").ifBlank { "待确认的沟通话题" },
                stage = json.optString("stage").toStage(),
                participantGoals = json.optJSONArray("goals").toStringList(),
                agreements = json.optJSONArray("agreements").toStringList(),
                disagreements = json.optJSONArray("disagreements").toStringList(),
                unresolvedIssues = json.optJSONArray("issues").toStringList(),
                communicationSignals = json.optJSONArray("signals").toStringList(),
                keyFacts = json.optJSONArray("facts").toStringList()
            ),
            actions = actions,
            repliesByAction = actions.associate { action ->
                action.id to replies?.optJSONArray(action.id).toReplies()
            },
            note = json.optString("note")
        )
    }

    private fun JSONArray?.toActions(): List<NextAction> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val item = optJSONObject(i) ?: return@mapNotNull null
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NextAction(
                id = item.optString("id").takeIf { it.isNotBlank() } ?: "action-$i",
                title = title,
                description = item.optString("description"),
                type = item.optString("type").toActionType(),
                reason = item.optString("reason"),
                priority = item.optInt("priority", i + 1)
            )
        }
    }

    private fun JSONArray?.toReplies(): List<ReplyCandidate> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val item = optJSONObject(i) ?: return@mapNotNull null
            val text = item.optString("text").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ReplyCandidate(item.optString("style").ifBlank { "自然" }, text)
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun String.toStage(): ConversationStage =
        ConversationStage.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
            ?: ConversationStage.UNKNOWN

    private fun String.toActionType(): ActionType =
        ActionType.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
            ?: ActionType.CLARIFY

    // Endpoint safety (cleartext refusal) lives in EndpointSafety.kt, shared with the reply
    // card's network client; the call site below resolves to that top-level extension.

    private companion object {
        const val TAG = "FlowAI"
        const val CONNECT_TIMEOUT_S = 10L
        const val READ_TIMEOUT_S = 60L

        /** How much of the original payload the follow-up chat re-sends as grounding. */
        const val MAX_GROUNDING_CHARS = 6_000

        /**
         * The reading instruction that accompanies a screenshot (see [userMessage]).
         *
         * Kept in the user turn rather than the system prompt: the text path must not carry
         * image wording, and the schema contract in [INSTRUCTION] is shared by both paths.
         */
        val IMAGE_INSTRUCTION = """
            这是一张聊天截屏。请先完整读出图中的对话内容（双方消息、称呼、时间等可见线索），
            再基于读出的对话按系统提示的 JSON 约定输出分析。只输出 JSON。
        """.trimIndent()

        /**
         * The output contract appended after the persona setting (see [systemPrompt]).
         *
         * A relay carries its own prompt; this one has to ask for exactly the shape the UI renders,
         * because a rambling answer would otherwise degrade into an unusable screen.
         */
        val INSTRUCTION = """
            你是沟通分析助手。阅读用户给出的聊天记录，只输出 JSON，不要解释、不要代码块标记。

            {
              "topic": "一句话概括这段对话在谈什么",
              "stage": "OPENING|DISCUSSION|NEGOTIATION|DECISION|FOLLOW_UP|CLOSING|UNKNOWN",
              "goals": ["各方想要什么"],
              "agreements": ["已达成的共识"],
              "disagreements": ["分歧"],
              "issues": ["尚未解决、需要跟进的问题"],
              "signals": ["沟通信号，如语气、紧迫度、隐含态度"],
              "facts": ["关键事实，如时间、地点、责任人"],
              "actions": [
                { "id": "a1", "title": "建议的下一步（不超过12字）", "description": "具体怎么做",
                  "type": "GIVE_DEADLINE|REPORT_PROGRESS|CLARIFY|EXTRACT_TASKS|CONFIRM|OTHER",
                  "reason": "为什么建议这个", "priority": 1 }
              ],
              "replies": {
                "a1": [ { "style": "自然", "text": "可直接发送的回复" },
                        { "style": "简洁", "text": "…" },
                        { "style": "正式", "text": "…" } ]
              }
            }

            要求：
            - actions 给 3 条，priority 依次 1、2、3。
            - replies 的键必须与 actions 的 id 一一对应，每条动作给三种风格。
            - 回复要直接可用，不要「您可以」「建议您」这类客套。
            - 信息不足时对应字段留空数组或 UNKNOWN，不要编造时间、地点、人名。
        """.trimIndent()
    }
}
