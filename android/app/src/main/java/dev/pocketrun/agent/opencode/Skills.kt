package dev.pocketrun.agent.opencode

import dev.pocketrun.core.Workspace
import java.io.File

/**
 * opencode Agent Skills: `SKILL.md` files with YAML frontmatter, discovered
 * from the project (`.opencode/skills/<name>/SKILL.md`, plus `.claude/` and
 * `.agents/` compatibility locations) and from the global config directory
 * (mapped to `<workspace>/opencode/skills/` on the device).
 *
 * Skills are advertised in the `skill` tool description as
 * `<available_skills>` XML and loaded on demand by the model.
 */
class Skills(private val workspace: Workspace) {

    data class Skill(val name: String, val description: String, val file: File, val global: Boolean) {
        val content: String by lazy { file.readText(Charsets.UTF_8) }
    }

    /** Global `~/.config/opencode/skills` analog. */
    val globalDir: File get() = File(workspace.root, "opencode/skills")

    /** Project skill locations, in opencode's search order. */
    private fun projectDirs(projectDir: File): List<File> = listOf(
        File(projectDir, ".opencode/skills"),
        File(projectDir, ".claude/skills"),
        File(projectDir, ".agents/skills"),
    )

    /**
     * All valid skills for [projectDir]: global first, then project ones.
     * Later entries with the same name are skipped (first wins, like opencode's
     * precedence rules in reverse order of discovery — global is the fallback).
     */
    fun discover(projectDir: File?): List<Skill> {
        val found = mutableListOf<Skill>()
        if (projectDir != null) {
            for (dir in projectDirs(projectDir)) found += scan(dir, global = false)
        }
        found += scan(globalDir, global = true)
        val seen = mutableSetOf<String>()
        return found.filter { seen.add(it.name) }
    }

    private fun scan(dir: File, global: Boolean): List<Skill> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isDirectory }
            .orEmpty()
            .mapNotNull { folder ->
                val file = File(folder, "SKILL.md")
                if (!file.isFile) return@mapNotNull null
                val front = parseFrontmatter(file.readText(Charsets.UTF_8))
                val name = front["name"]?.trim().orEmpty()
                val description = front["description"]?.trim().orEmpty()
                if (!nameMatches(folder.name) || name.isEmpty() || description.isEmpty()) null
                else Skill(name, description, file, global)
            }
            .sortedBy { it.name }
    }

    fun load(name: String, projectDir: File?): Skill? =
        discover(projectDir).firstOrNull { it.name == name }

    /** The `<available_skills>` block embedded in the tool description. */
    fun availableSkillsXml(projectDir: File?): String {
        val skills = discover(projectDir)
        if (skills.isEmpty()) return ""
        return buildString {
            append("<available_skills>\n")
            for (s in skills) {
                append("  <skill>\n")
                append("    <name>${s.name}</name>\n")
                append("    <description>${escape(s.description)}</description>\n")
                append("  </skill>\n")
            }
            append("</available_skills>\n")
        }
    }

    companion object {
        private val NAME_REGEX = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

        fun nameMatches(name: String): Boolean =
            name.length in 1..64 && NAME_REGEX.matches(name)

        /** Minimal YAML frontmatter parser: flat `key: value` lines. */
        fun parseFrontmatter(text: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            val lines = text.lines()
            if (lines.firstOrNull()?.trim() != "---") return out
            var i = 1
            while (i < lines.size && lines[i].trim() != "---") {
                val line = lines[i]
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    var value = line.substring(idx + 1).trim()
                    // continuation lines (indented, e.g. metadata maps) are ignored
                    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                        value = value.substring(1, value.length - 1)
                    }
                    if (key.isNotEmpty() && !out.containsKey(key)) out[key] = value
                }
                i++
            }
            return out
        }

        private fun escape(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
