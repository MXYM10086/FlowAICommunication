package com.flowai.communication.ai

import android.util.Log
import com.flowai.communication.BuildConfig
import com.flowai.communication.data.model.*
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
    private val fallback: LlmService = MockLlmService()
) : LlmService {

    /** The single response, kept for the follow-up calls the UI makes. */
    private var cached: Analysis? = null
    private var cachedFor: String? = null

    override suspend fun build(context: ContextCapsule): ConversationState {
        cached = null
        cachedFor = null
        val response = postAnalysis(context.rawText, context.sourceType.name)
        if (response == null) return fallback.build(context)
        val analysis = runCatching { parse(response) }
            .onFailure { Log.w(TAG, "analysis response rejected, using local engine", it) }
            .getOrNull()
        if (analysis == null) return fallback.build(context)
        cached = analysis
        cachedFor = context.rawText
        lastRawText = context.rawText
        return analysis.state
    }

    override suspend fun recommend(state: ConversationState): List<NextAction> {
        val cachedActions = cached?.actions.orEmpty()
        Log.i(
            TAG,
            "recommend: cached=${cached != null} actions=${cachedActions.size} " +
                "sameText=${cachedFor == lastRawText}"
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
            ?.takeIf { cachedFor == context.rawText }
            ?.repliesByAction
            ?.get(action.id)
            .orEmpty()
        if (replies.isEmpty()) return fallback.execute(context, state, action)
        return ActionResult(replies = replies, note = cached?.note.orEmpty())
    }

    // ---------------------------------------------------------------- request

    /** Sends the conversation and returns the parsed object, or null on any failure. */
    private suspend fun postAnalysis(text: String?, source: String): JSONObject? {
        val settings = settingsProvider()
        if (!settings.isConfigured) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(settings.providerUrl)
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
                    if (settings.apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                    }
                }
                try {
                    // The provider's protocol, plus an explicit request for JSON so the answer
                    // parses instead of arriving as prose the UI cannot render.
                    val body = JSONObject()
                        .put("model", settings.model)
                        .put("temperature", 0.3)
                        .put("response_format", JSONObject().put("type", "json_object"))
                        .put(
                            "messages",
                            JSONArray()
                                .put(JSONObject().put("role", "system").put("content", INSTRUCTION))
                                .put(JSONObject().put("role", "user").put("content", text.orEmpty()))
                        )
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        Log.w(TAG, "engine returned HTTP $code")
                        return@runCatching null
                    }
                    val raw = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                    val content = JSONObject(raw)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        .orEmpty()
                    JSONObject(stripCodeFence(content))
                        .also { Log.i(TAG, "engine ok, keys=${it.keys().asSequence().toList()}") }
                } finally {
                    connection.disconnect()
                }
            }.onFailure { Log.w(TAG, "engine request failed", it) }.getOrNull()
        }
    }

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

    /** The text the cached analysis belongs to, so a later call cannot read a stale result. */
    private var lastRawText: String? = null

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

    /**
     * Whether the endpoint may receive conversation text.
     *
     * https always. Cleartext only to addresses that cannot leave the local network, and only in a
     * debug build — the debug network security config is what permits the connection, so release
     * stays https-only even if this check passed.
     */
    private fun URL.isSecureOrLocal(): Boolean {
        if (protocol == "https") return true
        if (protocol != "http") return false
        if (!BuildConfig.DEBUG) return false
        return isLoopbackOrPrivate()
    }

    /**
     * True for loopback and the RFC1918 private ranges, plus the emulator's host alias.
     *
     * Spelled out as prefix checks rather than a table of literals: the ranges are a property of IP
     * addressing, not addresses belonging to anyone in particular.
     */
    private fun URL.isLoopbackOrPrivate(): Boolean {
        val h = host.orEmpty()
        if (h == "localhost" || h == "127.0.0.1" || h == "::1") return true
        // The host machine as seen from an Android emulator.
        if (h == "10.0.2.2") return true

        val octets = h.split('.')
        if (octets.size != 4) return false
        val first = octets[0].toIntOrNull() ?: return false
        val second = octets[1].toIntOrNull() ?: return false
        return when (first) {
            10 -> true                                   // 10.0.0.0/8
            172 -> second in 16..31                      // 172.16.0.0/12
            192 -> second == 168                         // 192.168.0.0/16
            else -> false
        }
    }

    private companion object {
        const val TAG = "FlowAI"
        const val CONNECT_TIMEOUT_S = 10L
        const val READ_TIMEOUT_S = 60L

        /**
         * Prompt used when the app talks to the provider directly.
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
