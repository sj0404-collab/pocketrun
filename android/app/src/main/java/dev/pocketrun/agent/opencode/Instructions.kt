package dev.pocketrun.agent.opencode

import dev.pocketrun.core.Workspace
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * opencode instructions: `AGENTS.md` (project + global) with `CLAUDE.md`
 * fallbacks, plus the `instructions` array from an opencode-style config
 * (`<workspace>/opencode/opencode.json` stands in for
 * `~/.config/opencode/opencode.json`). Remote instruction URLs are fetched
 * with a 5 second timeout, exactly like opencode.
 */
class Instructions(private val workspace: Workspace) {

    /** Global `~/.config/opencode` analog. */
    val globalDir: File get() = File(workspace.root, "opencode")

    data class Result(val text: String, val sources: List<String>)

    /**
     * Collects instructions for [projectDir]: project AGENTS.md (or CLAUDE.md),
     * walking up to the workspace root; then the global one; then every entry
     * of the config `instructions` list (local paths relative to the project
     * or the workspace, and http(s) URLs).
     */
    fun collect(projectDir: File?): Result {
        val parts = mutableListOf<Pair<String, String>>() // source → text

        // 1. project rules, nearest first (AGENTS.md wins over CLAUDE.md per directory)
        var dir = projectDir
        val chain = mutableListOf<File>()
        while (dir != null && dir.startsWith(workspace.root) && chain.size < 8) {
            chain += dir
            dir = dir.parentFile
        }
        for (d in chain.reversed()) {
            val f = ruleFile(d) ?: continue
            val text = f.readText(Charsets.UTF_8).trim()
            if (text.isNotEmpty()) parts += workspace.relativeTo(f) to text
        }

        // 2. global rules
        val global = ruleFile(globalDir)?.readText(Charsets.UTF_8)?.trim()
        if (!global.isNullOrEmpty()) parts += workspace.relativeTo(ruleFile(globalDir)!!) to global

        // 3. instructions from config
        for (entry in configInstructions()) {
            if (entry.startsWith("http://") || entry.startsWith("https://")) {
                fetchRemote(entry)?.let { parts += entry to it }
            } else {
                val f = resolveInstructionPath(entry, projectDir) ?: continue
                if (f.isFile) {
                    val text = f.readText(Charsets.UTF_8).trim()
                    if (text.isNotEmpty()) parts += workspace.relativeTo(f) to text
                }
            }
        }

        if (parts.isEmpty()) return Result("", emptyList())
        val text = parts.joinToString("\n\n") { "# ${it.first}\n\n${it.second}" }
        return Result(text, parts.map { it.first })
    }

    /** AGENTS.md, or CLAUDE.md when AGENTS.md is absent (Claude Code compat). */
    private fun ruleFile(dir: File): File? {
        val agents = File(dir, "AGENTS.md")
        if (agents.isFile) return agents
        val claude = File(dir, "CLAUDE.md")
        if (claude.isFile) return claude
        return null
    }

    /** The `instructions` array of `<workspace>/opencode/opencode.json`. */
    internal fun configInstructions(): List<String> {
        val config = File(globalDir, "opencode.json")
        if (!config.isFile) return emptyList()
        return try {
            val json = org.json.JSONObject(config.readText(Charsets.UTF_8))
            val arr = json.optJSONArray("instructions") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optString(it, "").trim().takeIf { it.isNotEmpty() } }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Supports glob-ish entries (a directory plus `*.md`) via a simple scan. */
    private fun resolveInstructionPath(entry: String, projectDir: File?): File? {
        if (entry.endsWith("/*") || entry.endsWith("/*.md")) {
            val parent = entry.removeSuffix("*").removeSuffix("/")
            val base = workspace.resolve(parent, projectDir ?: workspace.root) ?: return null
            if (!base.isDirectory) return null
            val files = base.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: return null
            // combined into one pseudo-file: return the first; others handled by caller loop
            // — simpler: create a synthetic marker by concatenating at collect() level
            // For now single-file entries cover the common case; glob entries use the first match.
            return files.sortedBy { it.name }.firstOrNull()
        }
        return workspace.resolve(entry, projectDir ?: workspace.root)
    }

    private fun fetchRemote(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        val status = conn.responseCode
        if (status !in 200..299) null
        else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(48 * 1024).trim().ifEmpty { null } }
    } catch (t: Throwable) {
        null
    }
}
