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
        assertTrue(tools.isReadOnly("github", JSONObject().put("op", "logs")))
        assertTrue(tools.isReadOnly("github", JSONObject().put("op", "artifacts")))
        assertTrue(tools.isReadOnly("github", JSONObject().put("op", "repo_list")))
        assertTrue(tools.isReadOnly("github", JSONObject().put("op", "file_get")))
        assertTrue(tools.isReadOnly("github", JSONObject().put("op", "me")))
        assertFalse(tools.isReadOnly("github", JSONObject().put("op", "push")))
        assertFalse(tools.isReadOnly("github", JSONObject().put("op", "repo_create")))
        assertFalse(tools.isReadOnly("github", JSONObject().put("op", "dispatch")))
        assertFalse(tools.isReadOnly("github", JSONObject().put("op", "api")))
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
    fun githubUnknownOpListsValidOnes() {
        val h = newTools(githubToken = "faketoken")
        val r = h.tools.execute("github", """{"op":"nonsense"}""")
        assertTrue(r.contains("неизвестная операция"))
        assertTrue(r.contains("repo_create"))
        assertTrue(r.contains("artifact_download"))
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
}
