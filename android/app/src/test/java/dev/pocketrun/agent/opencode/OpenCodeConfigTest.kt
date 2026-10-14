package dev.pocketrun.agent.opencode

import dev.pocketrun.core.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * opencode.json parsing: global/project merge, permission rules with glob
 * patterns, and custom agents/commands from both JSON and markdown folders.
 */
class OpenCodeConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newWorkspace(): Workspace = Workspace.at(tmp.newFolder())

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    @Test
    fun projectValuesWinOverGlobal() {
        val ws = newWorkspace()
        write(File(ws.root, "opencode/opencode.json"), """{"model":"global-model","permission":{"bash":"ask"}}""")
        val project = File(ws.root, "proj").apply { mkdirs() }
        write(File(project, ".opencode/opencode.json"), """{"model":"project-model","permission":{"bash":"allow"}}""")

        val cfg = OpenCodeConfig.load(ws, project)
        assertEquals("project-model", cfg.model)
        assertEquals(OpenCodeConfig.Permission.ALLOW, cfg.permissionFor("bash", "ls"))
    }

    @Test
    fun globalOnlyWhenProjectHasNoConfig() {
        val ws = newWorkspace()
        write(File(ws.root, "opencode/opencode.json"), """{"model":"global-model"}""")
        val project = File(ws.root, "proj").apply { mkdirs() }
        assertEquals("global-model", OpenCodeConfig.load(ws, project).model)
    }

    @Test
    fun patternedPermissionIsMostSpecificFirst() {
        val ws = newWorkspace()
        val project = File(ws.root, "proj").apply { mkdirs() }
        write(
            File(project, "opencode.json"),
            """{"permission":{"bash":{"*":"allow","rm *":"deny","git status":"ask"}}}""",
        )
        val cfg = OpenCodeConfig.load(ws, project)
        assertEquals(OpenCodeConfig.Permission.DENY, cfg.permissionFor("bash", "rm -rf build"))
        assertEquals(OpenCodeConfig.Permission.ALLOW, cfg.permissionFor("bash", "ls -la"))
        assertEquals(OpenCodeConfig.Permission.ASK, cfg.permissionFor("bash", "git status"))
    }

    @Test
    fun filePermissionMatchesPath() {
        val ws = newWorkspace()
        val project = File(ws.root, "proj").apply { mkdirs() }
        write(File(project, "opencode.json"), """{"permission":{"edit":{"*.md":"deny","*":"allow"}}}""")
        val cfg = OpenCodeConfig.load(ws, project)
        assertEquals(OpenCodeConfig.Permission.DENY, cfg.permissionFor("edit", "docs/README.md"))
        assertEquals(OpenCodeConfig.Permission.ALLOW, cfg.permissionFor("edit", "src/Main.kt"))
    }

    @Test
    fun toolAliasesShareRules() {
        val ws = newWorkspace()
        val project = File(ws.root, "proj").apply { mkdirs() }
        write(File(project, "opencode.json"), """{"permission":{"edit":"deny"}}""")
        val cfg = OpenCodeConfig.load(ws, project)
        assertEquals(OpenCodeConfig.Permission.DENY, cfg.permissionFor("edit", "a.kt"))
    }

    @Test
    fun missingRuleReturnsNull() {
        val cfg = OpenCodeConfig.EMPTY
        assertNull(cfg.permissionFor("bash", "ls"))
    }

    @Test
    fun agentFromMarkdown() {
        val ws = newWorkspace()
        val project = File(ws.root, "proj").apply { mkdirs() }
        write(
            File(project, ".opencode/agent/reviewer.md"),
            "---\ndescription: Reviews code\nmode: subagent\nmodel: gpt-4o\ntools: read, grep\n---\n\nYou review code carefully.",
        )
        val agent = OpenCodeConfig.load(ws, project).agent("reviewer")
        assertNotNull(agent)
        assertEquals("Reviews code", agent!!.description)
        assertEquals("subagent", agent.mode)
        assertEquals("gpt-4o", agent.model)
        assertEquals(setOf("read", "grep"), agent.tools)
        assertTrue(agent.prompt!!.contains("review code"))
    }

    @Test
    fun agentFromJson() {
        val ws = newWorkspace()
        val project = File(ws.root, "proj").apply { mkdirs() }
        write(
            File(project, "opencode.json"),
            """{"agent":{"planner":{"description":"Plans","model":"m","tools":["read","glob"],"prompt":"Plan it."}}}""",
        )
        val agent = OpenCodeConfig.load(ws, project).agent("planner")
        assertEquals("Plan it.", agent!!.prompt)
        assertEquals(setOf("read", "glob"), agent.tools)
    }

    @Test
    fun commandFromMarkdownAndJson() {
        val ws = newWorkspace()
        val project = File(ws.root, "proj").apply { mkdirs() }
        write(File(project, ".opencode/command/test.md"), "---\ndescription: Run tests\n---\n\nRun the tests for \$ARGUMENTS")
        write(File(project, "opencode.json"), """{"command":{"review":{"template":"Review this diff","agent":"planner"}}}""")

        val cfg = OpenCodeConfig.load(ws, project)
        assertEquals("Run tests", cfg.command("test")!!.description)
        assertTrue(cfg.command("test")!!.template.contains("Run the tests"))
        assertEquals("planner", cfg.command("review")!!.agent)
        assertEquals(listOf("review", "test"), cfg.commandNames())
    }

    @Test
    fun globMatching() {
        assertTrue(OpenCodeConfig.globMatches("*", "anything at all"))
        assertTrue(OpenCodeConfig.globMatches("rm *", "rm -rf x"))
        assertTrue(OpenCodeConfig.globMatches("*.md", "a/b/c.md"))
        assertTrue(!OpenCodeConfig.globMatches("rm *", "ls"))
        assertTrue(!OpenCodeConfig.globMatches("git status", "git push"))
    }

    @Test
    fun keyArgPerTool() {
        assertEquals("ls", OpenCodeConfig.keyArgFor("bash", org.json.JSONObject().put("command", "ls")))
        assertEquals("a.kt", OpenCodeConfig.keyArgFor("edit", org.json.JSONObject().put("filePath", "a.kt")))
        assertEquals("https://x", OpenCodeConfig.keyArgFor("webfetch", org.json.JSONObject().put("url", "https://x")))
    }
}
