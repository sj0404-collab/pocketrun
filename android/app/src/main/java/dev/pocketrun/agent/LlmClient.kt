package dev.pocketrun.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A thin OpenAI-compatible chat-completions client: POST JSON, read JSON, turn
 * the response into [Response] (text plus any tool calls). No streaming, no
 * retries — the agent loop decides what to do with failures.
 */
class LlmClient(private val config: AgentSettings.Config) {

    data class ToolCall(val id: String, val name: String, val arguments: String)
    data class Response(val content: String?, val toolCalls: List<ToolCall>)

    class LlmException(message: String) : Exception(message)

    fun chat(messages: JSONArray, tools: JSONArray?): Response {
        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("temperature", 0.2)
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }

        val conn = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 300_000 // models can think for a while before answering
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (status !in 200..299) {
            throw LlmException("HTTP $status от ${config.endpoint}: ${text.take(400)}")
        }

        val json = JSONObject(text)
        val message = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val content = if (message.isNull("content")) null else message.optString("content").takeIf { it.isNotEmpty() }

        val calls = mutableListOf<ToolCall>()
        val arr = message.optJSONArray("tool_calls")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val call = arr.getJSONObject(i)
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                if (name.isEmpty()) continue
                calls += ToolCall(
                    id = call.optString("id").ifEmpty { "call_$i" },
                    name = name,
                    arguments = fn.optString("arguments", "{}").ifEmpty { "{}" },
                )
            }
        }
        return Response(content, calls)
    }
}
