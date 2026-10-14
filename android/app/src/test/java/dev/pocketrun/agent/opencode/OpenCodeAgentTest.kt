package dev.pocketrun.agent.opencode

import dev.pocketrun.agent.AgentSettings
import dev.pocketrun.agent.FakeHttpServer
import dev.pocketrun.agent.LlmClient
import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.js.JsRuntime
import dev.pocketrun.runtime.npm.NpxRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The agent loop against a local OpenAI-compatible server: tool calls run and
 * come back, the round budget stops a runaway turn, an interrupted turn still
 * leaves a history the next request can be built on, and a fat history is
 * trimmed instead of blowing the model's context.
 */
class OpenCodeAgentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Ctx(val workspace: Workspace, val project: File, val tools: OpenCodeTools)

    private lateinit var ctx: Ctx

    private fun bootSource(): String = listOf(
        File("src/main/assets/node/boot.js"),
        File("app/src/main/assets/node/boot.js"),
    ).firstOrNull { it.isFile }?.readText(Charsets.UTF_8) ?: error("boot.js not found")

    private fun chunk(delta: JSONObject) = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)))

    private fun toolChunk(index: Int, id: String, name: String, args: String) = chunk(
        JSONObject().put(
            "tool_calls",
            JSONArray().put(
                JSONObject().put("index", index).put("id", id).put("type", "function")
                    .put("function", JSONObject().put("name", name).put("arguments", args)),
            ),
        ),
    )

    private fun sse(vararg chunks: JSONObject) =
        chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    /** A model that answers with the given SSE bodies, the last one repeating. */
    private fun serve(responses: List<String>): FakeHttpServer = FakeHttpServer(
        responses.map { FakeHttpServer.Resp("text/event-stream", it) },
    )

    private fun setup(): File {
        val ws = Workspace.at(tmp.newFolder())
        val project = File(ws.root, "proj").apply { mkdirs() }
        val js = JsRuntime(bootSource(), ws, maxRunMs = 10_000)
        ctx = Ctx(ws, project, OpenCodeTools(ws, js, NpxRuntime(js, ws), project, ""))
        return project
    }

    private fun agent(port: Int, maxSteps: Int, cancelled: () -> Boolean = { false }): OpenCodeAgent {
        val config = AgentSettings.Config("http://127.0.0.1:$port/v1", "", "test-model")
        return OpenCodeAgent(
            ctx.workspace, ctx.tools, { LlmClient(config) }, ctx.project,
            maxSteps = maxSteps, isCancelled = cancelled,
        )
    }

    /** Every tool call in the history must be answered by a tool message. */
    private fun assertValidHistory(history: JSONArray) {
        val pending = LinkedHashSet<String>()
        for (i in 0 until history.length()) {
            val m = history.getJSONObject(i)
            when (m.optString("role")) {
                "assistant" -> m.optJSONArray("tool_calls")?.let { calls ->
                    for (c in 0 until calls.length()) pending += calls.getJSONObject(c).optString("id")
                }
                "tool" -> assertTrue(
                    "tool_call ${m.optString("tool_call_id")} без соответствующего assistant-вызова",
                    pending.remove(m.optString("tool_call_id")),
                )
                "user" -> assertTrue("user-сообщение посреди неотвеченных вызовов", pending.isEmpty())
            }
        }
    }

    @Test
    fun toolCallIsExecutedAndTheAnswerEndsTheTurn() {
        val project = setup()
        val server = serve(
            listOf(
                sse(toolChunk(0, "c1", "write", """{"filePath":"note.txt","content":"привет из агента"}""")),
                sse(chunk(JSONObject().put("content", "Готово, файл записан"))),
            ),
        )
        val events = mutableListOf<OpenCodeAgent.Event>()
        try {
            val history = agent(server.port, maxSteps = 5).run("создай файл", JSONArray()) { events += it }
            assertEquals("привет из агента", File(project, "note.txt").readText())
            assertTrue(events.any { it is OpenCodeAgent.Event.ToolStart && it.name == "write" })
            assertTrue(events.any { it is OpenCodeAgent.Event.AssistantText && it.text.contains("Готово") })
            assertTrue(events.none { it is OpenCodeAgent.Event.Failed })
            assertEquals("Готово, файл записан", history.getJSONObject(history.length() - 1).optString("content"))
            assertValidHistory(history)
        } finally {
            server.close()
        }
    }

    @Test
    fun roundBudgetStopsARunawayTurnAndKeepsTheHistoryValid() {
        setup()
        val write = sse(toolChunk(0, "c1", "write", """{"filePath":"loop.txt","content":"x"}"""))
        val server = serve(listOf(write))
        val events = mutableListOf<OpenCodeAgent.Event>()
        try {
            val history = agent(server.port, maxSteps = 3).run("зациклись", JSONArray()) { events += it }
            assertEquals(3, events.count { it is OpenCodeAgent.Event.ToolStart })
            val failure = events.filterIsInstance<OpenCodeAgent.Event.Failed>().lastOrNull()
            assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("достигнут лимит раундов (3)"))
            assertValidHistory(history)
        } finally {
            server.close()
        }
    }

    @Test
    fun interruptedTurnAnswersEveryToolCall() {
        val project = setup()
        val server = serve(
            listOf(
                sse(
                    toolChunk(0, "c1", "write", """{"filePath":"one.txt","content":"1"}"""),
                    toolChunk(1, "c2", "write", """{"filePath":"two.txt","content":"2"}"""),
                ),
            ),
        )
        val cancelled = AtomicBoolean(false)
        val events = mutableListOf<OpenCodeAgent.Event>()
        try {
            val history = agent(server.port, maxSteps = 10, cancelled = { cancelled.get() }).run("пиши", JSONArray()) { event ->
                events += event
                if (event is OpenCodeAgent.Event.ToolDone) cancelled.set(true)
            }
            assertTrue(File(project, "one.txt").isFile)
            assertFalse("второй вызов не должен был выполниться", File(project, "two.txt").exists())
            assertTrue(events.filterIsInstance<OpenCodeAgent.Event.Failed>().last().message.contains("остановлено"))
            // The second call never ran, but the saved history must not keep a
            // tool call without an answer — strict providers reject that.
            assertValidHistory(history)
            val last = history.getJSONObject(history.length() - 1)
            assertEquals("tool", last.optString("role"))
            assertEquals("c2", last.optString("tool_call_id"))
            assertTrue(last.optString("content"), last.optString("content").contains("прервано"))
        } finally {
            server.close()
        }
    }

    @Test
    fun emptyAnswerIsNudgedInsteadOfEndingTheTurnInSilence() {
        setup()
        val server = serve(
            listOf(
                "data: [DONE]\n\n",
                sse(chunk(JSONObject().put("content", "теперь отвечаю"))),
            ),
        )
        val events = mutableListOf<OpenCodeAgent.Event>()
        try {
            val history = agent(server.port, maxSteps = 3).run("?", JSONArray()) { events += it }
            assertTrue(events.any { it is OpenCodeAgent.Event.AssistantText && it.text.contains("теперь отвечаю") })
            assertTrue(events.none { it is OpenCodeAgent.Event.Failed })
            assertTrue(history.toString().contains(OpenCodeAgent.CONTINUE_NUDGE))
            assertValidHistory(history)
        } finally {
            server.close()
        }
    }

    @Test
    fun fatHistoryIsTrimmedAndTheRequestStaysValid() {
        val project = setup()
        val server = serve(
            listOf(
                sse(toolChunk(0, "c1", "write", """{"filePath":"big.txt","content":"x"}""")),
                sse(chunk(JSONObject().put("content", "сделано"))),
            ),
        )
        val history = JSONArray().put(JSONObject().put("role", "user").put("content", "исходная задача"))
        repeat(30) { i ->
            history.put(
                JSONObject()
                    .put("role", "assistant")
                    .put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject().put("id", "old$i").put("type", "function")
                                .put("function", JSONObject().put("name", "read").put("arguments", """{"filePath":"f$i"}""")),
                        ),
                    ),
            )
            history.put(
                JSONObject().put("role", "tool").put("tool_call_id", "old$i")
                    .put("content", "вывод ".repeat(2_000) + i),
            )
        }
        val events = mutableListOf<OpenCodeAgent.Event>()
        try {
            val result = agent(server.port, maxSteps = 4).run("продолжай", history) { events += it }
            assertTrue(File(project, "big.txt").isFile)
            assertTrue("пользователь должен узнать о сокращении истории", events.any { it is OpenCodeAgent.Event.Note })
            assertValidHistory(result)
            // The saved session keeps everything, including the original request.
            assertEquals("исходная задача", result.getJSONObject(0).optString("content"))
        } finally {
            server.close()
        }
    }
}
