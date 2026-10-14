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
                for (f in resolveInstructionFiles(entry, projectDir)) {
                    if (!f.isFile) continue
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

    /**
     * Resolves a config `instructions` entry to one or more files. A plain path
     * resolves directly; glob entries (`*.md`, `docs/**/*.md`) are matched
     * against the project directory and the workspace root.
     */
    private fun resolveInstructionFiles(entry: String, projectDir: File?): List<File> {
        if (entry.none { it == '*' || it == '?' }) {
            return listOfNotNull(workspace.resolve(entry, projectDir ?: workspace.root))
        }
        val regex = globToRegex(entry)
        val roots = listOfNotNull(projectDir, workspace.root).distinct()
        val out = LinkedHashMap<String, File>()
        for (root in roots) {
            var seen = 0
            root.walkTopDown().forEach { f ->
                if (seen > 20_000) return@forEach
                seen++
                if (!f.isFile) return@forEach
                val rel = f.relativeToOrNull(root)?.path ?: return@forEach
                if (regex.matches(rel)) out.putIfAbsent(f.canonicalPath, f)
            }
        }
        return out.values.sortedBy { it.path }
    }

    /** `**` crosses directories, `*`/`?` stay within one path segment. */
    private fun globToRegex(pattern: String): Regex {
        val regex = buildString {
            append('^')
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                        if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                            append("(?:.*/)?")
                            i += 2
                        } else {
                            append(".*")
                            i++
                        }
                    }
                    c == '*' -> append("[^/]*")
                    c == '?' -> append("[^/]")
                    c in "\\^$.|+()[]{}" -> { append('\\'); append(c) }
                    else -> append(c)
                }
                i++
            }
            append('$')
        }
        return Regex(regex)
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
