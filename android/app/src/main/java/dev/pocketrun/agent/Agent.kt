package dev.pocketrun.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent loop: user message in, tool calls out, tool results back in,
 * repeat until the model answers with plain text (or hits the step budget).
 *
 * Conversation history is kept as an OpenAI messages array (without the system
 * message, which is prepended on every run) and returned to the caller so it
 * can be carried across turns.
 */
class Agent(
    private val tools: AgentTools,
    private val clientFactory: () -> LlmClient,
    private val maxSteps: Int = 10,
) {

    sealed class Event {
        data class AssistantText(val text: String) : Event()
        data class ToolStart(val name: String, val args: String) : Event()
        data class ToolDone(val name: String, val result: String) : Event()
        data class Failed(val message: String) : Event()
    }

    @Volatile
    var cancelled: Boolean = false

    /**
     * Runs one user turn. [history] is the messages array from previous turns
     * (assistant, tool, user — no system message); the returned array is the
     * updated history including this turn.
     */
    fun run(userText: String, history: JSONArray, onEvent: (Event) -> Unit): JSONArray {
        val messages = JSONArray()
        messages.put(systemMessage())
        for (i in 0 until history.length()) messages.put(history.get(i))
        messages.put(JSONObject().put("role", "user").put("content", userText))

        var newMessages = JSONArray()
        try {
            for (step in 0 until maxSteps) {
                if (cancelled) {
                    onEvent(Event.Failed("отменено пользователем"))
                    break
                }
                val response = clientFactory().chat(messages, tools.definitions())

                if (response.toolCalls.isEmpty()) {
                    val text = response.content.orEmpty()
                    messages.put(JSONObject().put("role", "assistant").put("content", text))
                    if (text.isNotBlank()) onEvent(Event.AssistantText(text))
                    newMessages = stripSystem(messages)
                    return newMessages
                }

                val assistantMsg = JSONObject().put("role", "assistant")
                if (response.content != null) {
                    assistantMsg.put("content", response.content)
                    if (response.content.isNotBlank()) onEvent(Event.AssistantText(response.content))
                }
                val calls = JSONArray()
                response.toolCalls.forEach { call ->
                    calls.put(
                        JSONObject().apply {
                            put("id", call.id)
                            put("type", "function")
                            put(
                                "function",
                                JSONObject().put("name", call.name).put("arguments", call.arguments),
                            )
                        },
                    )
                }
                assistantMsg.put("tool_calls", calls)
                messages.put(assistantMsg)

                for (call in response.toolCalls) {
                    if (cancelled) break
                    onEvent(Event.ToolStart(call.name, prettyArgs(call.arguments)))
                    val result = tools.execute(call.name, call.arguments)
                    onEvent(Event.ToolDone(call.name, result))
                    messages.put(
                        JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", call.id)
                            put("content", result)
                        },
                    )
                }
            }
            if (!cancelled && newMessages.length() == 0) {
                onEvent(Event.Failed("достигнут лимит шагов ($maxSteps) без финального ответа — уточните запрос"))
            }
        } catch (t: Throwable) {
            onEvent(Event.Failed(t.message ?: t.javaClass.simpleName))
        }
        return stripSystem(messages)
    }

    private fun stripSystem(messages: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.get(i)
            if (m is JSONObject && m.optString("role") == "system") continue
            out.put(m)
        }
        return out
    }

    private fun prettyArgs(argsJson: String): String {
        return try {
            val obj = JSONObject(argsJson)
            if (obj.length() == 0) "" else obj.toString()
        } catch (t: Throwable) {
            argsJson.take(200)
        }
    }

    private fun systemMessage(): JSONObject {
        return JSONObject().put(
            "role",
            "system",
        ).put(
            "content",
            """
            You are PocketRun Agent, a coding assistant running fully offline-capable inside the PocketRun mobile app (Android, no internet required except for the LLM API itself).

            Environment:
            - A file sandbox: projects live under projects/<name>/, npm packages under packages/, shared caches under cache/. All tool paths are relative to the sandbox root or absolute inside it.
            - You can run code: Python 3.13 (standard library only) and JavaScript (a Node.js-compatible subset: require/module.exports, fs, timers, Buffer, http/https, process, url, console — no async/await, no native addons).
            - You can run pure-JS npm packages via run_npx (like `npx <pkg> [args]`). Packages with native addons or heavy ES features will not work — say so instead of retrying.
            - Network access from user code is available through http(s) requests.

            How to work:
            - Prefer doing: read files, run code, iterate on errors, then summarize what you did.
            - Keep code small and focused; show the user the important parts.
            - If a tool fails, read the error and adjust; don't repeat the same failing call more than twice.
            - Answer in the user's language (usually Russian). Be concise.
            """.trimIndent(),
        )
    }
}
