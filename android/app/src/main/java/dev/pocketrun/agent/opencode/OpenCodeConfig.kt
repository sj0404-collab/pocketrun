package dev.pocketrun.agent.opencode

import dev.pocketrun.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The opencode config, read from `opencode.json` plus the `.opencode/` folders.
 *
 * Two locations are merged: the global one (`<workspace>/opencode/opencode.json`
 * stands in for `~/.config/opencode/opencode.json` on the device) and the
 * project one (`.opencode/opencode.json`, or `opencode.json` in the project
 * root). Project values win over global ones, key by key.
 *
 * What is actually used by the app:
 *  - `model`       — the model the agent uses for this project;
 *  - `permission`  — per-tool `allow` / `ask` / `deny`, with `bash`-style glob
 *                    patterns for a finer grain (e.g. `{ "rm *": "deny" }`);
 *  - `agent`       — custom agents (`.opencode/agent/<name>.md` too): prompt, model,
 *                    tool allow-list, usable as primary or as a `task` type;
 *  - `command`     — custom slash commands (`.opencode/command/<name>.md` too).
 */
class OpenCodeConfig private constructor(
    val model: String?,
    private val permissions: Map<String, PermissionRule>,
    val agents: Map<String, AgentDef>,
    val commands: Map<String, CommandDef>,
) {

    enum class Permission { ALLOW, ASK, DENY }

    sealed interface PermissionRule {
        data class Simple(val permission: Permission) : PermissionRule

        /** Patterns in "most specific first" order; the first match wins. */
        data class Patterned(val entries: List<Pair<String, Permission>>) : PermissionRule
    }

    data class AgentDef(
        val name: String,
        val description: String,
        val mode: String?,
        val model: String?,
        val prompt: String?,
        val tools: Set<String>?,
        val global: Boolean,
        val file: File?,
    )

    data class CommandDef(
        val name: String,
        val description: String,
        val template: String,
        val agent: String?,
        val model: String?,
        val global: Boolean,
    )

    /**
     * The configured permission for a tool call, or null when the config says
     * nothing about it (the caller then applies its own default).
     *
     * [keyArg] is the value patterns are matched against — the bash command,
     * the file path, the URL, the github op, the task type.
     */
    fun permissionFor(tool: String, keyArg: String): Permission? =
        when (val rule = permissions[normalizeTool(tool)]) {
            null -> null
            is PermissionRule.Simple -> rule.permission
            is PermissionRule.Patterned -> rule.entries.firstOrNull { globMatches(it.first, keyArg) }?.second
        }

    fun agent(name: String): AgentDef? = agents[name]

    fun command(name: String): CommandDef? = commands[name]

    /** Every command name, sorted, for `/help`. */
    fun commandNames(): List<String> = commands.keys.sorted()

    /** Every agent name, sorted, for `/agents`. */
    fun agentNames(): List<String> = agents.keys.sorted()

    companion object {
        /** Agent names that ship with the app (also valid `task` types). */
        val BUILTIN_AGENTS = listOf("build", "plan", "explore", "general")

        private val TOOL_ALIASES = mapOf(
            "edit" to "edit", "write" to "write", "patch" to "apply_patch",
            "apply_patch" to "apply_patch", "bash" to "bash", "read" to "read",
            "list" to "list", "glob" to "glob", "grep" to "grep",
            "webfetch" to "webfetch", "skill" to "skill", "question" to "question",
            "todowrite" to "todowrite", "github" to "github", "task" to "task",
        )

        val EMPTY = OpenCodeConfig(null, emptyMap(), emptyMap(), emptyMap())

        fun load(workspace: Workspace, projectDir: File?): OpenCodeConfig {
            val global = File(workspace.root, "opencode")
            val globalJson = File(global, "opencode.json")
            val projectJson = projectDir?.let { dir ->
                listOf(File(dir, ".opencode/opencode.json"), File(dir, "opencode.json"))
                    .firstOrNull { it.isFile }
            }

            val model = projectJson?.let { optString(it, "model") }
                ?: optString(globalJson, "model")

            val permissions = LinkedHashMap<String, PermissionRule>()
            parsePermission(globalJson)?.let { permissions.putAll(it) }
            parsePermission(projectJson)?.let { permissions.putAll(it) }

            val agents = LinkedHashMap<String, AgentDef>()
            agents.putAll(scanAgentDir(File(global, "agent"), global = true))
            if (projectDir != null) agents.putAll(scanAgentDir(File(projectDir, ".opencode/agent"), global = false))
            mergeAgentJson(agents, globalJson, global = true)
            mergeAgentJson(agents, projectJson, global = false)

            val commands = LinkedHashMap<String, CommandDef>()
            commands.putAll(scanCommandDir(File(global, "command"), global = true))
            if (projectDir != null) commands.putAll(scanCommandDir(File(projectDir, ".opencode/command"), global = false))
            mergeCommandJson(commands, globalJson, global = true)
            mergeCommandJson(commands, projectJson, global = false)

            return OpenCodeConfig(model, permissions, agents, commands)
        }

        /** Bash/glob patterns: `*` and `?`, anchored at both ends. */
        fun globMatches(pattern: String, value: String): Boolean {
            val regex = buildString {
                append('^')
                for (c in pattern) {
                    when (c) {
                        '*' -> append(".*")
                        '?' -> append('.')
                        else -> if (c in "\\^$.|+()[]{}") { append('\\'); append(c) } else append(c)
                    }
                }
                append('$')
            }
            return Regex(regex).matches(value)
        }

        /** The argument patterns are matched against, per tool. */
        fun keyArgFor(tool: String, args: JSONObject): String = when (tool) {
            "bash" -> args.optString("command")
            "read", "write", "edit" -> args.optString("filePath")
            "list", "glob", "grep" -> args.optString("path")
            "webfetch" -> args.optString("url")
            "skill" -> args.optString("name")
            "github" -> args.optString("op")
            "task" -> args.optString("subagent_type")
            else -> ""
        }

        // ------------------------------------------------------------ parsing

        private fun normalizeTool(tool: String): String = TOOL_ALIASES[tool] ?: tool

        private fun parsePermission(file: File?): Map<String, PermissionRule>? {
            val json = readJson(file) ?: return null
            val obj = json.optJSONObject("permission") ?: return null
            val out = LinkedHashMap<String, PermissionRule>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val tool = keys.next()
                when (val value = obj.opt(tool)) {
                    is String -> parsePermissionValue(value)?.let { out[normalizeTool(tool)] = PermissionRule.Simple(it) }
                    is JSONObject -> {
                        val entries = mutableListOf<Pair<String, Permission>>()
                        val pKeys = value.keys()
                        while (pKeys.hasNext()) {
                            val pattern = pKeys.next()
                            parsePermissionValue(value.optString(pattern))?.let { entries += pattern to it }
                        }
                        if (entries.isNotEmpty()) {
                            // Most specific pattern first, so `rm *` beats `*`.
                            entries.sortByDescending { it.first.replace("*", "").replace("?", "").length }
                            out[normalizeTool(tool)] = PermissionRule.Patterned(entries)
                        }
                    }
                }
            }
            return out
        }

        private fun parsePermissionValue(raw: String?): Permission? = when (raw?.trim()?.lowercase()) {
            "allow", "true", "yes" -> Permission.ALLOW
            "ask", "confirm" -> Permission.ASK
            "deny", "false", "no" -> Permission.DENY
            else -> null
        }

        private fun mergeAgentJson(into: MutableMap<String, AgentDef>, file: File?, global: Boolean) {
            val json = readJson(file) ?: return
            val obj = json.optJSONObject("agent") ?: return
            val keys = obj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                if (!Skills.nameMatches(name)) continue
                val a = obj.optJSONObject(name) ?: continue
                into[name] = AgentDef(
                    name = name,
                    description = a.optString("description"),
                    mode = a.optString("mode").takeIf { it.isNotBlank() },
                    model = a.optString("model").takeIf { it.isNotBlank() },
                    prompt = a.optString("prompt").takeIf { it.isNotBlank() },
                    tools = parseTools(a.opt("tools")),
                    global = global,
                    file = file,
                )
            }
        }

        private fun mergeCommandJson(into: MutableMap<String, CommandDef>, file: File?, global: Boolean) {
            val json = readJson(file) ?: return
            val obj = json.optJSONObject("command") ?: return
            val keys = obj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val c = obj.optJSONObject(name) ?: continue
                val template = c.optString("template")
                if (template.isBlank()) continue
                into[name] = CommandDef(
                    name = name,
                    description = c.optString("description"),
                    template = template,
                    agent = c.optString("agent").takeIf { it.isNotBlank() },
                    model = c.optString("model").takeIf { it.isNotBlank() },
                    global = global,
                )
            }
        }

        private fun parseTools(value: Any?): Set<String>? = when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
            is String -> value.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            is JSONObject -> {
                // opencode's { "bash": true, "edit": false } form.
                val out = mutableSetOf<String>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (value.optBoolean(k, false)) out += k
                }
                out
            }
            else -> null
        }?.takeIf { it.isNotEmpty() }

        // ------------------------------------------------------------ folders

        private fun scanAgentDir(dir: File, global: Boolean): List<Pair<String, AgentDef>> {
            if (!dir.isDirectory) return emptyList()
            return dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty().mapNotNull { file ->
                val name = file.name.removeSuffix(".md")
                if (!Skills.nameMatches(name)) return@mapNotNull null
                val (front, body) = readMarkdown(file)
                val a = AgentDef(
                    name = name,
                    description = front["description"].orEmpty(),
                    mode = front["mode"]?.takeIf { it.isNotBlank() },
                    model = front["model"]?.takeIf { it.isNotBlank() },
                    prompt = body.takeIf { it.isNotBlank() },
                    tools = front["tools"]?.let { parseTools(it) },
                    global = global,
                    file = file,
                )
                name to a
            }
        }

        private fun scanCommandDir(dir: File, global: Boolean): List<Pair<String, CommandDef>> {
            if (!dir.isDirectory) return emptyList()
            return dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty().mapNotNull { file ->
                val name = file.name.removeSuffix(".md")
                if (name.isBlank()) return@mapNotNull null
                val (front, body) = readMarkdown(file)
                if (body.isBlank()) return@mapNotNull null
                name to CommandDef(
                    name = name,
                    description = front["description"].orEmpty(),
                    template = body,
                    agent = front["agent"]?.takeIf { it.isNotBlank() },
                    model = front["model"]?.takeIf { it.isNotBlank() },
                    global = global,
                )
            }
        }

        /** Frontmatter (reusing the skill parser) plus the body after it. */
        private fun readMarkdown(file: File): Pair<Map<String, String>, String> {
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
            val front = Skills.parseFrontmatter(text)
            if (!text.startsWith("---")) return front to text.trim()
            val lines = text.lines()
            var i = 1
            while (i < lines.size && lines[i].trim() != "---") i++
            val body = if (i < lines.size) lines.drop(i + 1).joinToString("\n") else ""
            return front to body.trim()
        }

        private fun readJson(file: File?): JSONObject? {
            if (file == null || !file.isFile) return null
            return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
        }

        private fun optString(file: File?, key: String): String? {
            val json = readJson(file) ?: return null
            return json.optString(key).trim().takeIf { it.isNotEmpty() }
        }
    }
}
