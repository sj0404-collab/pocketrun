package dev.pocketrun.agent.opencode

import dev.pocketrun.core.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Instruction collection: project AGENTS.md (walking up to the workspace
 * root), CLAUDE.md fallback, the global workspace/opencode/AGENTS.md and the
 * `instructions` array of opencode.json.
 */
class InstructionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newWorkspace(): Workspace = Workspace.at(tmp.newFolder())

    @Test
    fun projectAgentsMdOnly() {
        val ws = newWorkspace()
        val project = File(ws.root, "projects/demo")
        project.mkdirs()
        File(project, "AGENTS.md").writeText("Правила проекта demo.")

        val r = Instructions(ws).collect(project)
        assertEquals(1, r.sources.size)
        assertTrue(r.text.contains("Правила проекта demo."))
    }

    @Test
    fun claudeMdFallsBack() {
        val ws = newWorkspace()
        val project = File(ws.root, "projects/demo")
        project.mkdirs()
        File(project, "CLAUDE.md").writeText("Claude rules.")

        val r = Instructions(ws).collect(project)
        assertTrue(r.text.contains("Claude rules."))
        assertTrue(r.sources.first().contains("CLAUDE.md"))
    }

    @Test
    fun agentsBeatsClaude() {
        val ws = newWorkspace()
        val project = File(ws.root, "projects/demo")
        project.mkdirs()
        File(project, "AGENTS.md").writeText("AGENTS wins.")
        File(project, "CLAUDE.md").writeText("CLAUDE loses.")

        val r = Instructions(ws).collect(project)
        assertTrue(r.text.contains("AGENTS wins."))
        assertTrue(!r.text.contains("CLAUDE loses."))
    }

    @Test
    fun globalPlusProjectChain() {
        val ws = newWorkspace()
        File(ws.root, "opencode").mkdirs()
        File(ws.root, "opencode/AGENTS.md").writeText("Глобальные правила.")
        val project = File(ws.root, "projects/demo")
        project.mkdirs()
        File(project, "AGENTS.md").writeText("Проектные правила.")

        val r = Instructions(ws).collect(project)
        assertTrue(r.text.contains("Проектные правила."))
        assertTrue(r.text.contains("Глобальные правила."))
        // nearest (project) source comes last in the chain — outer first
        assertEquals(2, r.sources.size)
    }

    @Test
    fun configInstructionsArray() {
        val ws = newWorkspace()
        val project = File(ws.root, "projects/demo")
        project.mkdirs()
        File(project, "extra.md").writeText("Доп. инструкции.")
        File(ws.root, "opencode").mkdirs()
        File(ws.root, "opencode/opencode.json").writeText("""{"instructions": ["extra.md"]}""")

        val r = Instructions(ws).collect(project)
        assertTrue(r.text.contains("Доп. инструкции."))
    }

    @Test
    fun emptyWhenNothingConfigured() {
        val ws = newWorkspace()
        val r = Instructions(ws).collect(File(ws.root, "projects/x"))
        assertEquals("", r.text)
        assertTrue(r.sources.isEmpty())
    }
}
