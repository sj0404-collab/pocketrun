package dev.pocketrun.agent.opencode

import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.js.JsRuntime
import dev.pocketrun.runtime.npm.NpxRuntime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The manual-confirmation gate: read-only tools run without asking, mutating
 * tools ask the approver and respect its verdict; the github tool degrades to
 * a clear error without a token.
 */
class OpenCodeToolsGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bootSource(): String {
        val candidates = listOf(
            File("src/main/assets/node/boot.js"),
            File("app/src/main/assets/node/boot.js"),
        )
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8) ?: error("boot.js not found")
    }

    private class Harness(val tools: OpenCodeTools, val projectDir: File)

    private fun newTools(githubToken: String = ""): Harness {
        val ws = Workspace.at(tmp.newFolder())
        val project = File(ws.root, "proj").apply { mkdirs() }
        val js = JsRuntime(bootSource(), ws, maxRunMs = 10_000)
        return Harness(OpenCodeTools(ws, js, NpxRuntime(js, ws), project, githubToken), project)
    }

    @Test
    fun readOnlyToolsNeverAsk() {
        val h = newTools()
        val asked = mutableListOf<String>()
        h.tools.toolApprover = { name, _ ->
            asked += name
            true
        }
        File(h.projectDir, "a.txt").writeText("hi")
        val r = h.tools.execute("read", """{"filePath":"a.txt"}""")
        assertTrue(r.contains("hi"))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun mutatingToolRespectsApproval() {
        val h = newTools()
        var verdict = false
        h.tools.toolApprover = { _, _ -> verdict }

        val denied = h.tools.execute("write", """{"filePath":"x.txt","content":"no"}""")
        assertEquals("(отклонено пользователем)", denied)
        assertFalse(File(h.projectDir, "x.txt").exists())

        verdict = true
        val allowed = h.tools.execute("write", """{"filePath":"x.txt","content":"yes"}""")
        assertTrue(allowed.contains("wrote"))
        assertEquals("yes", File(h.projectDir, "x.txt").readText())
    }

    @Test
    fun isReadOnlyClassification() {
        val tools = newTools().tools
        assertTrue(tools.isReadOnly("read", JSONObject()))
        assertTrue(tools.isReadOnly("grep", JSONObject()))
        assertTrue(tools.isReadOnly("question", JSONObject()))
        assertTrue(tools.isReadOnly("todowrite", JSONObject()))
        assertFalse(tools.isReadOnly("bash", JSONObject()))
        assertFalse(tools.isReadOnly("write", JSONObject()))
        assertFalse(tools.isReadOnly("edit", JSONObject()))
        assertFalse(tools.isReadOnly("apply_patch", JSONObject()))

        assertTrue(tools.isReadOnly("github", JSONObject().put("op", "runs")))
        assertTrue(tools.isReadOnly("github", JSONObject().put("op", "run_wait")))
        assertTrue(tools.isReadOnly("github", JSONObject().put("op", "logs")))
        assertTrue(tools.isReadOnly("github", JSONObject().put("op", "artifacts")))
        assertTrue(tools.isReadOnly("github", JSONObject().put("op", "repo_list")))
        assertTrue(tools.isReadOnly("github", JSONObject().put("op", "file_get")))
        assertTrue(tools.isReadOnly("github", JSONObject().put("op", "me")))
        assertFalse(tools.isReadOnly("github", JSONObject().put("op", "push")))
        assertFalse(tools.isReadOnly("github", JSONObject().put("op", "file_delete")))
        assertFalse(tools.isReadOnly("github", JSONObject().put("op", "repo_create")))
        assertFalse(tools.isReadOnly("github", JSONObject().put("op", "dispatch")))
        assertFalse(tools.isReadOnly("github", JSONObject().put("op", "run_cancel")))
        assertFalse(tools.isReadOnly("github", JSONObject().put("op", "api")))
    }

    @Test
    fun githubUnknownOpListsTheWaitingAndCommitOps() {
        val h = newTools(githubToken = "faketoken")
        val r = h.tools.execute("github", """{"op":"nonsense"}""")
        assertTrue(r.contains("неизвестная операция"))
        assertTrue(r.contains("repo_create"))
        assertTrue(r.contains("run_wait"))
        assertTrue(r.contains("file_delete"))
        assertTrue(r.contains("artifact_download"))
    }

    @Test
    fun toolDefinitionsCoverTheNewGithubOps() {
        val tools = newTools().tools
        val defs = tools.definitions().toString()
        assertTrue(defs.contains("run_wait"))
        assertTrue(defs.contains("run_cancel"))
        assertTrue(defs.contains("file_delete"))
        assertTrue(defs.contains("timeout_sec"))
        // The description must tell the model not to poll.
        assertTrue(defs.contains("run_wait"))
        assertTrue(defs.contains("ONE commit"))
    }

    @Test
    fun bashTimeoutIsGenerousEnoughForInstalls() {
        assertTrue(OpenCodeTools.BASH_TIMEOUT_MS >= 300_000L)
        val h = newTools()
        val r = h.tools.execute("bash", """{"command":"echo ok","timeout":999999}""")
        assertTrue(r.contains("ok"))
    }

    @Test
    fun githubWithoutTokenIsAClearError() {
        val h = newTools(githubToken = "")
        h.tools.toolApprover = { _, _ -> true }
        val r = h.tools.execute("github", """{"op":"repo_list"}""")
        assertTrue(r.contains("GitHub-токен не задан"))
        assertTrue(r.contains("⚙"))
    }

    @Test
    fun approverSeesTheToolName() {
        val h = newTools()
        val seen = mutableListOf<String>()
        h.tools.toolApprover = { name, _ ->
            seen += name
            false
        }
        h.tools.execute("bash", """{"command":"echo hi"}""")
        assertEquals(listOf("bash"), seen)
    }

    @Test
    fun taskToolIsOfferedOnlyWhenARunnerIsSet() {
        val h = newTools()
        assertFalse(h.tools.definitions().toString().contains("\"name\":\"task\""))

        h.tools.subagentRunner = { "done" }
        assertTrue(h.tools.definitions().toString().contains("\"name\":\"task\""))
    }

    @Test
    fun taskToolHandsThePromptToTheRunner() {
        val h = newTools()
        var got: OpenCodeTools.TaskRequest? = null
        h.tools.subagentRunner = { req ->
            got = req
            "result: ${req.subagentType}"
        }
        val r = h.tools.execute("task", """{"description":"look","prompt":"find X","subagent_type":"explore"}""")
        assertEquals("result: explore", r)
        assertEquals("find X", got?.prompt)
        assertEquals("explore", got?.subagentType)
    }

    @Test
    fun taskToolWithoutPromptIsAnError() {
        val h = newTools()
        h.tools.subagentRunner = { "unused" }
        assertTrue(h.tools.execute("task", """{"description":"x","subagent_type":"general"}""").contains("нужен непустой prompt"))
    }

    @Test
    fun allowedToolsRestrictBothDefinitionsAndCalls() {
        val h = newTools()
        File(h.projectDir, "a.txt").writeText("hi")
        h.tools.allowedTools = OpenCodeTools.subagentTools("explore")

        val defs = h.tools.definitions().toString()
        assertTrue(defs.contains("\"name\":\"read\""))
        assertFalse(defs.contains("\"name\":\"write\""))
        assertFalse(defs.contains("\"name\":\"bash\""))

        assertTrue(h.tools.execute("read", """{"filePath":"a.txt"}""").contains("hi"))
        assertTrue(h.tools.execute("write", """{"filePath":"b.txt","content":"x"}""").contains("недоступен"))
    }

    @Test
    fun exploreSubagentCannotMutateGeneralCan() {
        val explore = OpenCodeTools.subagentTools("explore")
        assertTrue("read" in explore)
        assertFalse("write" in explore)
        assertFalse("bash" in explore)
        assertFalse("task" in explore)

        val general = OpenCodeTools.subagentTools("general")
        assertTrue("write" in general)
        assertTrue("bash" in general)
        assertFalse("task" in general)
        assertFalse("question" in general)
    }

    @Test
    fun configDenyBlocksAWrite() {
        val h = newTools()
        h.tools.permissionResolver = { _, _ -> OpenCodeConfig.Permission.DENY }
        val r = h.tools.execute("write", """{"filePath":"x.txt","content":"no"}""")
        assertTrue(r.startsWith("(запрещено"))
        assertFalse(File(h.projectDir, "x.txt").exists())
    }

    @Test
    fun configAskUsesTheApprover() {
        val h = newTools()
        h.tools.permissionResolver = { _, _ -> OpenCodeConfig.Permission.ASK }
        var asked = false
        h.tools.toolApprover = { _, _ ->
            asked = true
            true
        }
        h.tools.execute("write", """{"filePath":"x.txt","content":"yes"}""")
        assertTrue(asked)
        assertEquals("yes", File(h.projectDir, "x.txt").readText())
    }

    @Test
    fun askWithoutAnApproverIsRefused() {
        val h = newTools()
        h.tools.permissionResolver = { _, _ -> OpenCodeConfig.Permission.ASK }
        val r = h.tools.execute("write", """{"filePath":"x.txt","content":"no"}""")
        assertTrue(r.contains("нужно подтверждение"))
        assertFalse(File(h.projectDir, "x.txt").exists())
    }

    @Test
    fun resolverOverridesDefaultForARead() {
        val h = newTools()
        File(h.projectDir, "a.txt").writeText("hi")
        h.tools.permissionResolver = { _, _ -> OpenCodeConfig.Permission.ASK }
        h.tools.toolApprover = { _, _ -> false }
        assertEquals("(отклонено пользователем)", h.tools.execute("read", """{"filePath":"a.txt"}"""))
    }

    @Test
    fun onBeforeWriteFiresForEachChangedFile() {
        val h = newTools()
        val seen = mutableListOf<String>()
        h.tools.onBeforeWrite = { f -> seen += f.name }
        File(h.projectDir, "a.txt").writeText("one")
        h.tools.execute("write", """{"filePath":"a.txt","content":"two"}""")
        h.tools.execute("edit", """{"filePath":"a.txt","oldString":"two","newString":"three"}""")
        h.tools.execute("apply_patch", """{"patchText":"*** Begin Patch\n*** Add File: b.txt\n+hi\n*** End Patch"}""")
        assertTrue(seen.contains("a.txt"))
        assertTrue(seen.contains("b.txt"))
    }
}
