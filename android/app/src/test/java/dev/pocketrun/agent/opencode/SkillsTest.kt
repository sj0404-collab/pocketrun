package dev.pocketrun.agent.opencode

import dev.pocketrun.core.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SKILL.md discovery: frontmatter parsing, project (.opencode/.claude/.agents)
 * and global (workspace/opencode/skills) locations, the <available_skills>
 * XML block and on-demand loading.
 */
class SkillsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newWorkspace(): Workspace = Workspace.at(tmp.newFolder())

    private fun writeSkill(root: File, location: String, name: String, description: String, body: String = "Do it.") {
        val dir = File(root, "$location/$name")
        dir.mkdirs()
        File(dir, "SKILL.md").writeText(
            "---\nname: $name\ndescription: \"$description\"\n---\n\n$body\n",
            Charsets.UTF_8,
        )
    }

    @Test
    fun frontmatterParsing() {
        val map = Skills.parseFrontmatter("---\nname: my-skill\ndescription: \"A test\"\n---\n\nBody")
        assertEquals("my-skill", map["name"])
        assertEquals("A test", map["description"])
        assertTrue(Skills.parseFrontmatter("no frontmatter").isEmpty())
    }

    @Test
    fun nameRules() {
        assertTrue(Skills.nameMatches("pdf"))
        assertTrue(Skills.nameMatches("create-pdf-2"))
        assertFalse(Skills.nameMatches("Bad_Name"))
        assertFalse(Skills.nameMatches("с пробелом"))
        assertFalse(Skills.nameMatches(""))
    }

    @Test
    fun globalAndProjectDiscovery() {
        val ws = newWorkspace()
        writeSkill(ws.root, "opencode/skills", "global-helper", "Helps globally")
        val project = File(ws.root, "projects/demo")
        project.mkdirs()
        writeSkill(project, ".opencode/skills", "proj-skill", "A project skill")
        writeSkill(project, ".claude/skills", "claude-compat", "From .claude")

        val found = Skills(ws).discover(project)
        val names = found.map { it.name }
        assertTrue(names.contains("global-helper"))
        assertTrue(names.contains("proj-skill"))
        assertTrue(names.contains("claude-compat"))
        assertTrue(found.first { it.name == "global-helper" }.global)
        assertFalse(found.first { it.name == "proj-skill" }.global)
    }

    @Test
    fun projectSkillShadowsGlobal() {
        val ws = newWorkspace()
        writeSkill(ws.root, "opencode/skills", "duper", "global version")
        val project = File(ws.root, "projects/demo")
        project.mkdirs()
        writeSkill(project, ".opencode/skills", "duper", "project version")
        val skill = Skills(ws).load("duper", project)
        assertNotNull(skill)
        assertFalse(skill!!.global)
        assertTrue(skill.content.contains("project version"))
    }

    @Test
    fun invalidSkillsSkipped() {
        val ws = newWorkspace()
        // missing description
        File(ws.root, "opencode/skills/nodesc").mkdirs()
        File(ws.root, "opencode/skills/nodesc/SKILL.md").writeText("---\nname: nodesc\n---\nbody")
        // folder name mismatch
        File(ws.root, "opencode/skills/BadName").mkdirs()
        File(ws.root, "opencode/skills/BadName/SKILL.md").writeText("---\nname: other\nother: x\ndescription: d\n---\nbody")
        assertTrue(Skills(ws).discover(null).isEmpty())
    }

    @Test
    fun availableSkillsXml() {
        val ws = newWorkspace()
        writeSkill(ws.root, "opencode/skills", "pdf-tools", "Work with <PDFs>")
        val xml = Skills(ws).availableSkillsXml(null)
        assertTrue(xml.contains("<available_skills>"))
        assertTrue(xml.contains("<name>pdf-tools</name>"))
        assertTrue(xml.contains("Work with &lt;PDFs&gt;"))

        // and empty when no skills exist
        val empty = Skills(Workspace.at(tmp.newFolder()))
        assertEquals("", empty.availableSkillsXml(null))
        assertTrue(empty.load("nope", null) == null)
    }
}
