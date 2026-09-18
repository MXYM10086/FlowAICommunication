package com.flowai.communication.ai

import android.util.Log
import com.flowai.communication.data.model.*
import com.flowai.communication.domain.ChatToActionEngine
import com.flowai.communication.domain.ConversationStateBuilder
import com.flowai.communication.domain.NextActionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Talks to a relay that fronts the model provider.
 *
 * The app holds no provider credential: the relay does, and this class only knows the relay's URL.
 * That keeps the key out of the APK, where it would be readable by anyone who unzips it.
 *
 * Every call falls back to [MockLlmService] when the relay is unconfigured or the request fails.
 * A capture or a share must never dead-end because a network call went wrong — the user
 * already has the text on screen and needs *something* back.
 */
class RemoteLlmService(
    private val settingsProvider: () -> EngineSettings,
    private val fallback: LlmService = MockLlmService()
) : LlmService {

    override suspend fun build(context: ContextCapsule): ConversationState {
        val body = JSONObject()
            .put("task", TASK_STATE)
            .put("text", context.rawText)
            .put("source", context.sourceType.name)
        val response = post(body) ?: return fallback.build(context)
        return runCatching { parseState(response, context) }
            .onFailure { Log.w(TAG, "state response rejected, using local engine", it) }
            .getOrElse { fallback.build(context) }
    }

    override suspend fun recommend(state: ConversationState): List<NextAction> {
        val body = JSONObject()
            .put("task", TASK_ACTIONS)
            .put("topic", state.topic)
            .put("issues", JSONArray(state.unresolvedIssues))
            .put("goals", JSONArray(state.participantGoals))
        val response = post(body) ?: return fallback.recommend(state)
        return runCatching { parseActions(response) }
            .onFailure { Log.w(TAG, "actions response rejected, using local engine", it) }
            .getOrElse { fallback.recommend(state) }
    }

    override suspend fun execute(
        context: ContextCapsule,
        state: ConversationState,
        action: NextAction
    ): ActionResult {
        val body = JSONObject()
            .put("task", TASK_EXECUTE)
            .put("text", context.rawText)
            .put("topic", state.topic)
            .put("action", action.title)
            .put("actionType", action.type.name)
        val response = post(body) ?: return fallback.execute(context, state, action)
        return runCatching { parseResult(response) }
            .onFailure { Log.w(TAG, "execute response rejected, using local engine", it) }
            .getOrElse { fallback.execute(context, state, action) }
    }

    /**
     * Sends one request, returning the parsed object or null.
     *
     * Never throws: every failure mode (unconfigured, offline, timeout, non-2xx, malformed JSON)
     * ends in null so the caller can fall back.
     */
    private suspend fun post(body: JSONObject): JSONObject? {
        val settings = settingsProvider()
        if (!settings.isConfigured) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(settings.endpoint)
                if (url.protocol != "https" && !url.host.isLocalAddress()) {
                    // Conversation text is personal; refuse to send it in the clear.
                    Log.w(TAG, "refusing non-https endpoint: ${url.protocol}")
                    return@runCatching null
                }
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TimeUnit.SECONDS.toMillis(CONNECT_TIMEOUT_S).toInt()
                    readTimeout = TimeUnit.SECONDS.toMillis(READ_TIMEOUT_S).toInt()
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    if (settings.accessToken.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${settings.accessToken}")
                    }
                }
                try {
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        Log.w(TAG, "engine returned HTTP $code")
                        return@runCatching null
                    }
                    val text = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                    JSONObject(text)
                } finally {
                    connection.disconnect()
                }
            }.onFailure { Log.w(TAG, "engine request failed", it) }.getOrNull()
        }
    }

    private fun parseState(json: JSONObject, context: ContextCapsule): ConversationState {
        return ConversationState(
            topic = json.getString("topic"),
            stage = json.optString("stage").toStage(),
            participantGoals = json.optJSONArray("goals").toStringList(),
            agreements = json.optJSONArray("agreements").toStringList(),
            disagreements = json.optJSONArray("disagreements").toStringList(),
            unresolvedIssues = json.optJSONArray("issues").toStringList(),
            communicationSignals = json.optJSONArray("signals").toStringList(),
            keyFacts = json.optJSONArray("facts").toStringList()
        )
    }

    private fun parseActions(json: JSONObject): List<NextAction> {
        val array = json.optJSONArray("actions") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NextAction(
                id = item.optString("id").takeIf { it.isNotBlank() } ?: "remote-$i",
                title = title,
                description = item.optString("description"),
                type = item.optString("type").toActionType(),
                reason = item.optString("reason"),
                priority = item.optInt("priority", i + 1)
            )
        }
    }

    private fun parseResult(json: JSONObject): ActionResult {
        val replies = json.optJSONArray("replies")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val text = item.optString("text").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ReplyCandidate(item.optString("style", "自然"), text)
            }
        }.orEmpty()
        return ActionResult(replies = replies, note = json.optString("note"))
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

    private fun String.isLocalAddress(): Boolean =
        this == "localhost" || this == "127.0.0.1" || this == "10.0.2.2" || endsWith(".local")

    private companion object {
        const val TAG = "FlowAI"
        const val TASK_STATE = "state"
        const val TASK_ACTIONS = "actions"
        const val TASK_EXECUTE = "execute"
        const val CONNECT_TIMEOUT_S = 10L
        const val READ_TIMEOUT_S = 30L
    }
}
