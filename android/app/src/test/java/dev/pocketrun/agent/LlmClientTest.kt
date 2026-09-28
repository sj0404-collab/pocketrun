package dev.pocketrun.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The chat client against a local OpenAI-compatible server: SSE streams
 * (including tool calls that arrive in pieces), plain JSON answers from servers
 * that ignore `stream`, an empty stream, cancellation between chunks and errors
 * that must not kill the turn silently.
 */
class LlmClientTest {

    private fun client(port: Int) = LlmClient(AgentSettings.Config("http://127.0.0.1:$port/v1", "", "test-model"))

    private fun messages(text: String = "привет") = JSONArray()
        .put(JSONObject().put("role", "system").put("content", "system"))
        .put(JSONObject().put("role", "user").put("content", text))

    private fun sse(vararg chunks: JSONObject): String =
        chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun delta(content: String) = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", JSONObject().put("content", content))))

    private fun toolDelta(index: Int, id: String?, name: String?, args: String?) = JSONObject().put(
        "choices",
        JSONArray().put(
            JSONObject()
                .put("index", 0)
                .put(
                    "delta",
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject().put("index", index).apply {
                                if (id != null) put("id", id)
                                put(
                                    "function",
                                    JSONObject().apply {
                                        if (name != null) put("name", name)
                                        if (args != null) put("arguments", args)
                                    },
                                )
                            },
                        ),
                    ),
                ),
        ),
    )

    @Test
    fun plainJsonAnswerIsParsed() {
        val server = FakeHttpServer(
            listOf(
                FakeHttpServer.Resp("application/json", """{"choices":[{"message":{"role":"assistant","content":"обычный ответ"}}]}"""),
            ),
        )
        try {
            val r = client(server.port).chat(messages(), null)
            assertEquals("обычный ответ", r.content)
            assertTrue(r.toolCalls.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun sseTextAndToolCallsAreAssembled() {
        val server = FakeHttpServer(
            listOf(
                FakeHttpServer.Resp(
                    "text/event-stream",
                    sse(
                        delta("При"),
                        delta("вет, "),
                        delta("всё сделано."),
                        toolDelta(0, "call_1", "bash", """{"command":""""),
                        toolDelta(0, null, null, """echo hi"}"""),
                    ),
                ),
            ),
        )
        try {
            val r = client(server.port).chat(messages(), null)
            assertEquals("Привет, всё сделано.", r.content)
            assertEquals(1, r.toolCalls.size)
            assertEquals("call_1", r.toolCalls[0].id)
            assertEquals("bash", r.toolCalls[0].name)
            assertEquals("""{"command":"echo hi"}""", r.toolCalls[0].arguments)
        } finally {
            server.close()
        }
    }

    @Test
    fun parallelToolCallsKeepTheirOrder() {
        val server = FakeHttpServer(
            listOf(
                FakeHttpServer.Resp(
                    "text/event-stream",
                    sse(
                        toolDelta(0, "a", "read", """{"filePath":"a.txt"}"""),
                        toolDelta(1, "b", "read", """{"filePath":"b.txt"}"""),
                    ),
                ),
            ),
        )
        try {
            val r = client(server.port).chat(messages(), null)
            assertEquals(listOf("a", "b"), r.toolCalls.map { it.id })
            assertEquals(listOf("read", "read"), r.toolCalls.map { it.name })
        } finally {
            server.close()
        }
    }

    @Test
    fun theRequestCarriesModelToolsAndHistory() {
        val server = FakeHttpServer(listOf(FakeHttpServer.Resp("application/json", """{"choices":[{"message":{"content":"ок"}}]}""")))
        try {
            val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "read")))
            client(server.port).chat(messages("сделай файл"), tools)
            val sent = server.requests.single()
            assertTrue(sent.contains("\"model\":\"test-model\""))
            assertTrue(sent.contains("\"stream\":true"))
            assertTrue(sent.contains("\"tools\""))
            assertTrue(sent.contains("сделай файл"))
        } finally {
            server.close()
        }
    }

    @Test
    fun emptyStreamComesBackAsAnEmptyAnswer() {
        // Not an error: the agent nudges the model and spends one round instead
        // of burning three retries on a request that will not change.
        val server = FakeHttpServer(listOf(FakeHttpServer.Resp("text/event-stream", "data: [DONE]\n\n")))
        try {
            val r = client(server.port).chat(messages(), null)
            assertEquals(null, r.content)
            assertTrue(r.toolCalls.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun htmlErrorPageWithStatus200IsAnError() {
        val server = FakeHttpServer(listOf(FakeHttpServer.Resp("text/html", "<html><body>gateway</body></html>")))
        try {
            client(server.port).chat(messages(), null)
            fail("ожидалась ошибка разбора ответа")
        } catch (e: LlmClient.LlmException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("ожидался JSON"))
        } finally {
            server.close()
        }
    }

    @Test
    fun errorInsideTheStreamIsReported() {
        val err = JSONObject().put("error", JSONObject().put("message", "model is overloaded"))
        val server = FakeHttpServer(listOf(FakeHttpServer.Resp("text/event-stream", sse(err))))
        try {
            client(server.port).chat(messages(), null)
            fail("ожидалась ошибка модели")
        } catch (e: LlmClient.LlmException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("model is overloaded"))
        } finally {
            server.close()
        }
    }

    @Test
    fun reasoningOnlyAnswerBecomesContent() {
        val chunk = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put("index", 0).put("delta", JSONObject().put("reasoning_content", "думаю…")),
            ),
        )
        val server = FakeHttpServer(listOf(FakeHttpServer.Resp("text/event-stream", sse(chunk))))
        try {
            assertEquals("думаю…", client(server.port).chat(messages(), null).content)
        } finally {
            server.close()
        }
    }

    @Test
    fun cancellationBeforeTheRequestDoesNothing() {
        val server = FakeHttpServer(listOf(FakeHttpServer.Resp("text/event-stream", sse(delta("неважно")))))
        try {
            val e = runCatching { client(server.port).chat(messages(), null, isCancelled = { true }) }.exceptionOrNull()
            assertTrue("ожидалась отмена, а получили $e", e is LlmClient.LlmException)
            assertEquals(LlmClient.CANCELLED, e?.message)
            assertTrue("запрос не должен был уйти", server.requests.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun cancellationBetweenChunksStopsTheStream() {
        // The server keeps the answer open for a second after the first chunk:
        // the client must give up on the first poll instead of waiting it out.
        val server = FakeHttpServer(
            listOf(FakeHttpServer.Resp("text/event-stream", sse(delta("начало"), delta(" и конец")), pauseMs = 900)),
        )
        try {
            var seen = 0
            val e = runCatching {
                client(server.port).chat(messages(), null, isCancelled = { seen > 0 }) { seen++ }
            }.exceptionOrNull()
            assertTrue("ожидалась отмена, а получили $e", e is LlmClient.LlmException)
            assertEquals(LlmClient.CANCELLED, e?.message)
            assertEquals(1, seen)
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectedKeyIsNotRetriedAndNamesTheStatus() {
        val server = FakeHttpServer(
            listOf(FakeHttpServer.Resp("application/json", """{"error":{"message":"bad key"}}""", status = 401)),
        )
        try {
            val e = runCatching { client(server.port).chat(messages(), null) }.exceptionOrNull()
            assertTrue("$e", e is LlmClient.LlmException)
            assertTrue(e!!.message.orEmpty(), e.message.orEmpty().contains("401"))
            assertEquals("401 не повторяется", 1, server.requests.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun aTransientServerErrorIsRetriedAndTheTurnSurvives() {
        val server = FakeHttpServer(
            listOf(
                FakeHttpServer.Resp("application/json", """{"message":"boom"}""", status = 503),
                FakeHttpServer.Resp("application/json", """{"choices":[{"message":{"content":"со второй попытки"}}]}"""),
            ),
        )
        try {
            assertEquals("со второй попытки", client(server.port).chat(messages(), null).content)
            assertEquals(2, server.requests.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun aDeadServerIsReportedWithAReadableMessage() {
        val server = FakeHttpServer(
            listOf(FakeHttpServer.Resp("application/json", """{"message":"boom"}""", status = 503)),
        )
        val port = server.port
        server.close()
        val e = runCatching { client(port).chat(messages(), null) }.exceptionOrNull()
        assertTrue("$e", e is LlmClient.LlmException)
        // Nothing is listening now: the user must see a network error, not a crash.
        assertTrue(e!!.message.orEmpty(), e.message.orEmpty().isNotBlank())
    }
}
