package dev.pocketrun.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A thin OpenAI-compatible chat-completions client: POST JSON, read JSON, turn
 * the response into [Response] (text plus any tool calls).
 *
 * Three things matter for a mobile agent loop and are implemented here:
 *
 * * **Streaming.** The request asks for `stream: true` and the answer is read
 *   as SSE, so a generation that takes many minutes keeps the connection busy
 *   (idle connections get killed by gateways and mobile networks), the ⏹ button
 *   is honoured between chunks, and the UI can show what is being written. A
 *   server that answers with a plain JSON body instead is handled transparently.
 * * **Retries.** Timeouts, resets, 429 and 5xx are retried with a backoff;
 *   everything else (bad key, unknown model) fails immediately.
 * * **Long deadlines.** A model that thinks for a long time must not lose the
 *   turn: the read timeout is 15 minutes, and a watchdog aborts a connection
 *   that goes silent for much longer than that.
 */
class LlmClient(private val config: AgentSettings.Config) {

    data class ToolCall(val id: String, val name: String, val arguments: String)
    data class Response(val content: String?, val toolCalls: List<ToolCall>)

    class LlmException(message: String) : Exception(message)

    /** The server answered, but not in a form we can use — worth one plain retry. */
    private class UnusableAnswer(val detail: String) : Exception(detail)

    /** A non-2xx answer, kept structured so retries can look at the status. */
    private class HttpFailure(val status: Int, val body: String) : Exception("HTTP $status")

    companion object {
        const val CONNECT_TIMEOUT_MS = 30_000

        /** 15 minutes — a long generation must not lose the turn. */
        const val READ_TIMEOUT_MS = 900_000

        /** No bytes for this long (and none at all since the request) = dead connection. */
        const val FIRST_TOKEN_TIMEOUT_MS = 240_000

        /** A stream that goes quiet for this long after the first token is dead too. */
        const val STALL_TIMEOUT_MS = 300_000

        const val MAX_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 3_000L
        const val CANCELLED = "остановлено пользователем"

        private const val SSE_DATA = "data:"
        private const val SSE_DONE = "[DONE]"

        /**
         * Fetches the model list of an OpenAI-compatible server:
         * GET {baseUrl}/models, parse data[].id. Used by the model settings UI
         * (e.g. the OpenCode Zen catalog at https://opencode.ai/zen/v1).
         */
        fun models(baseUrl: String, apiKey: String): List<String> {
            val url = baseUrl.trim().trimEnd('/') + "/models"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            try {
                val status = conn.responseCode
                val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (status !in 200..299) {
                    throw LlmException("HTTP $status от $url: ${text.take(300)}")
                }
                val data = JSONObject(text).optJSONArray("data") ?: return emptyList()
                val ids = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotEmpty()) ids += id
                }
                return ids.sortedWith(compareBy<String> { it.lowercase() })
            } finally {
                conn.disconnect()
            }
        }

        /**
         * Accumulates an OpenAI-style SSE stream: text deltas plus tool calls
         * whose name and arguments arrive in pieces. Kept separate from the
         * socket code so it can be tested without a server.
         */
        internal class Stream(
            private val onDelta: ((String) -> Unit)? = null,
        ) {
            private val text = StringBuilder()
            private val reasoning = StringBuilder()
            private val calls = LinkedHashMap<Int, Call>()

            private class Call {
                var id: String = ""
                var name: String = ""
                val arguments = StringBuilder()
            }

            /** Feeds one `data:` payload; malformed chunks are ignored. */
            fun feed(payload: String) {
                val json = try {
                    JSONObject(payload)
                } catch (t: Throwable) {
                    return
                }
                json.optJSONObject("error")?.let { err ->
                    val msg = err.optString("message").ifEmpty { payload.take(200) }
                    throw LlmException("сервер модели вернул ошибку: $msg")
                }
                val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
                // `delta` for streams, `message` for servers that stream nothing.
                val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return
                delta.optString("content").takeIf { it.isNotEmpty() }?.let {
                    text.append(it)
                    onDelta?.invoke(it)
                }
                // Reasoning models stream their thinking separately; keep it so a
                // blank `content` still produces something useful.
                delta.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let { reasoning.append(it) }
                val arr = delta.optJSONArray("tool_calls") ?: return
                for (i in 0 until arr.length()) {
                    val chunk = arr.optJSONObject(i) ?: continue
                    val slot = calls.getOrPut(chunk.optInt("index", i)) { Call() }
                    chunk.optString("id").takeIf { it.isNotEmpty() }?.let { slot.id = it }
                    val fn = chunk.optJSONObject("function") ?: continue
                    fn.optString("name").takeIf { it.isNotEmpty() }?.let {
                        slot.name = if (slot.name.isEmpty()) it else slot.name + it
                    }
                    fn.optString("arguments").takeIf { it.isNotEmpty() }?.let { slot.arguments.append(it) }
                }
            }

            fun isEmpty(): Boolean = text.isBlank() && reasoning.isBlank() && calls.isEmpty()

            /**
             * The finished answer. A well-formed but empty stream yields an
             * empty [Response] on purpose: the agent nudges the model and spends
             * one round, which beats three retries of a request that will not
             * change. Only a structurally broken answer is an error.
             */
            fun result(): Response {
                val toolCalls = calls.entries.sortedBy { it.key }.map { (i, c) ->
                    ToolCall(
                        id = c.id.ifEmpty { "call_$i" },
                        name = c.name,
                        arguments = c.arguments.toString().ifBlank { "{}" },
                    )
                }.filter { it.name.isNotEmpty() }
                val content = text.toString().ifBlank { reasoning.toString() }.ifBlank { null }
                return Response(content, toolCalls)
            }
        }

        /** Turns a streamed or plain assistant message into [Response]. */
        internal fun parseMessage(message: JSONObject): Response {
            val content = if (message.isNull("content")) {
                null
            } else {
                message.optString("content").takeIf { it.isNotEmpty() }
            }
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

        /** A user-readable, actionable description of a failed request. */
        internal fun describe(t: Throwable): String = when (t) {
            is LlmException -> t.message ?: "ошибка модели"
            is HttpFailure -> {
                val hint = when (t.status) {
                    401 -> " — ключ отвергнут сервером"
                    403 -> " — доступ запрещён (у Zen нужен бесплатный ключ с opencode.ai/auth)"
                    404 -> " — нет такого endpoint или модели"
                    413 -> " — запрос слишком велик (история разрослась)"
                    429 -> " — лимит запросов, попробуйте позже"
                    in 500..599 -> " — сервер модели перегружен"
                    else -> ""
                }
                "HTTP ${t.status}$hint: ${t.body.take(300)}"
            }
            is SocketTimeoutException ->
                "модель не ответила за ${READ_TIMEOUT_MS / 60_000} мин — попробуйте другую модель или упростите запрос"
            is IOException -> t.message ?: "сетевая ошибка: ${t.javaClass.simpleName}"
            else -> t.message ?: t.javaClass.simpleName
        }

        internal fun isRetryable(t: Throwable): Boolean = when (t) {
            is SocketTimeoutException, is IOException -> true
            is HttpFailure -> t.status == 408 || t.status == 429 || t.status in 500..599
            is UnusableAnswer -> true
            else -> false
        }
    }

    /**
     * One chat completion. [onDelta] receives text pieces as they arrive so the
     * UI can show progress; [isCancelled] is polled between chunks and before
     * every retry, which is what makes ⏹ stop a running turn.
     */
    fun chat(
        messages: JSONArray,
        tools: JSONArray?,
        isCancelled: () -> Boolean = { false },
        onDelta: ((String) -> Unit)? = null,
    ): Response {
        var attempt = 0
        var stream = true
        var backoff = RETRY_BACKOFF_MS
        while (true) {
            if (isCancelled()) throw LlmException(CANCELLED)
            attempt++
            try {
                return request(messages, tools, stream, isCancelled, if (stream) onDelta else null)
            } catch (t: Throwable) {
                if (isCancelled()) throw LlmException(CANCELLED)
                // The server ignored `stream` (or answered with something odd):
                // spend the same attempt on a plain, non-streamed request.
                if (t is UnusableAnswer && stream) {
                    stream = false
                    attempt--
                    continue
                }
                if (attempt >= MAX_ATTEMPTS || !isRetryable(t)) throw LlmException(describe(t))
                nap(backoff, isCancelled)
                backoff *= 2
            }
        }
    }

    // ---------------------------------------------------------------- request

    private fun request(
        messages: JSONArray,
        tools: JSONArray?,
        stream: Boolean,
        isCancelled: () -> Boolean,
        onDelta: ((String) -> Unit)?,
    ): Response {
        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("temperature", 0.2)
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            if (stream) put("stream", true)
        }

        val conn = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", if (stream) "text/event-stream, application/json" else "application/json")
            // No Authorization header without a key: local servers reject
            // stray headers far less often than cloud ones reject bad keys.
            if (config.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
        }
        val watchdog = Watchdog(conn)
        watchdog.start()
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..299) {
                throw HttpFailure(status, readText(conn.errorStream))
            }
            watchdog.beat()
            return readAnswer(conn, watchdog, isCancelled, onDelta)
        } catch (e: IOException) {
            if (watchdog.fired) throw SocketTimeoutException("нет данных от сервера модели")
            throw e
        } finally {
            watchdog.stop()
            conn.disconnect()
        }
    }

    /**
     * Reads the answer line by line: SSE chunks when the server streams them,
     * otherwise the whole body as one JSON document.
     */
    private fun readAnswer(
        conn: HttpURLConnection,
        watchdog: Watchdog,
        isCancelled: () -> Boolean,
        onDelta: ((String) -> Unit)?,
    ): Response {
        val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
        val stream = Stream(onDelta)
        val plain = StringBuilder()
        var sawChunk = false
        while (true) {
            if (isCancelled()) throw LlmException(CANCELLED)
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            if (line.startsWith(SSE_DATA)) {
                sawChunk = true
                val payload = line.removePrefix(SSE_DATA).trim()
                if (payload == SSE_DONE) break
                stream.feed(payload)
            } else if (!sawChunk) {
                // Not a stream: accumulate the plain JSON body.
                plain.append(line)
            }
            watchdog.beat()
        }
        if (sawChunk) return stream.result()
        val body = plain.toString().trim()
        if (body.isEmpty()) throw UnusableAnswer("сервер вернул пустой ответ")
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            throw UnusableAnswer("ожидался JSON, получено: ${body.take(120)}")
        }
        json.optJSONObject("error")?.let { err ->
            throw LlmException("сервер модели вернул ошибку: ${err.optString("message").ifEmpty { body.take(200) }}")
        }
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) throw UnusableAnswer("в ответе нет choices: ${body.take(200)}")
        return parseMessage(choices.getJSONObject(0).getJSONObject("message"))
    }

    private fun readText(stream: java.io.InputStream?): String =
        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

    /** Interruptible backoff between retries. */
    private fun nap(ms: Long, isCancelled: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) throw LlmException(CANCELLED)
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LlmException(CANCELLED)
            }
        }
    }

    /**
     * Closes a connection that stopped delivering bytes. HttpURLConnection's
     * read timeout is a per-read value, so a server that stops mid-answer would
     * otherwise hold the turn for the full deadline.
     */
    private class Watchdog(private val conn: HttpURLConnection) {
        private val lastData = AtomicLong(System.currentTimeMillis())
        private val gotData = AtomicBoolean(false)

        /** True when the watchdog, not the server, ended the connection. */
        @Volatile
        var fired: Boolean = false

        @Volatile
        private var stopped: Boolean = false

        fun start() {
            Thread({
                while (!stopped) {
                    Thread.sleep(2_000)
                    if (stopped) return@Thread
                    val idle = System.currentTimeMillis() - lastData.get()
                    val limit = if (gotData.get()) STALL_TIMEOUT_MS else FIRST_TOKEN_TIMEOUT_MS
                    if (idle > limit) {
                        fired = true
                        runCatching { conn.disconnect() }
                        return@Thread
                    }
                }
            }, "llm-watchdog").apply {
                isDaemon = true
                start()
            }
        }

        /** Called for every piece of data that arrived. */
        fun beat() {
            lastData.set(System.currentTimeMillis())
            gotData.set(true)
        }

        fun stop() {
            stopped = true
        }
    }
}
