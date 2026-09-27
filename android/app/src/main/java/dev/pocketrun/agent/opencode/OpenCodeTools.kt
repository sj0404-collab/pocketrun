package dev.pocketrun.agent.opencode

import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.js.JsRuntime
import dev.pocketrun.runtime.npm.NpxRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The opencode tool set, with opencode's names and semantics: bash, edit,
 * write, read, list, glob, grep, apply_patch, todowrite, webfetch, skill and
 * question. Everything runs inside the workspace sandbox; the `question`
 * tool surfaces an interactive prompt in the chat UI.
 */
class OpenCodeTools(
    private val workspace: Workspace,
    jsRuntime: JsRuntime,
    npxRuntime: NpxRuntime,
    private val projectDir: File?,
) {

    companion object {
        private const val MAX_READ = 32 * 1024
        private const val MAX_TOOL_OUTPUT = 16 * 1024
        private const val MAX_WEB = 24 * 1024
        private const val BASH_TIMEOUT_MS = 120_000L
    }

    private val shell = MiniShell(workspace, jsRuntime, npxRuntime)
    private val skills = Skills(workspace)

    /** Answers pending questions; set by the agent runner. */
    @Volatile
    var questionAsker: ((List<Question>) -> String)? = null

    /** Todo updates flow to the session and UI through this hook. */
    @Volatile
    var onTodos: ((List<Sessions.Todo>) -> Unit)? = null

    /** The current todo list; seeded from the session before each turn. */
    var todos: List<Sessions.Todo> = emptyList()

    data class Question(val header: String?, val question: String, val options: List<String>)

    fun definitions(): JSONArray = JSONArray().apply {
        fun tool(name: String, description: String, properties: JSONObject, required: List<String>) {
            put(
                JSONObject().apply {
                    put("type", "function")
                    put(
                        "function",
                        JSONObject().apply {
                            put("name", name)
                            put("description", description)
                            put(
                                "parameters",
                                JSONObject().apply {
                                    put("type", "object")
                                    put("properties", properties)
                                    put("required", JSONArray(required))
                                },
                            )
                        },
                    )
                },
            )
        }

        fun str(name: String, desc: String) = JSONObject().put("type", "string").put("description", desc)
        fun int(name: String, desc: String) = JSONObject().put("type", "integer").put("description", desc)
        fun bool(name: String, desc: String) = JSONObject().put("type", "boolean").put("description", desc)
        fun arr(name: String, desc: String, itemSchema: JSONObject = str("item", "")) =
            JSONObject().put("type", "array").put("items", itemSchema).put("description", desc)

        tool(
            "bash",
            "Execute a shell command in the sandboxed project directory. Available commands: ${MiniShell.COMMANDS.joinToString(", ")}. " +
                "python runs CPython 3.13 (stdlib only), node runs the Node-compatible layer, npx installs/runs pure-JS npm packages. " +
                "Supports quoting, && , | and > >> redirection. No git, no package managers other than npx.",
            JSONObject()
                .put("command", str("command", "the shell command to run"))
                .put("timeout", int("timeout", "optional timeout in milliseconds (max 120000)")),
            listOf("command"),
        )
        tool(
            "read",
            "Read a text file (max ${MAX_READ} chars). Supports optional offset (1-based line) and limit.",
            JSONObject()
                .put("filePath", str("filePath", "path relative to the project root"))
                .put("offset", int("offset", "1-based line number to start from"))
                .put("limit", int("limit", "number of lines to read")),
            listOf("filePath"),
        )
        tool(
            "write",
            "Create or overwrite a file with the given content. Parent directories are created automatically.",
            JSONObject()
                .put("filePath", str("filePath", "path relative to the project root"))
                .put("content", str("content", "full file content")),
            listOf("filePath", "content"),
        )
        tool(
            "edit",
            "Edit an existing file by replacing an exact string. oldString must be unique in the file unless replaceAll is true.",
            JSONObject()
                .put("filePath", str("filePath", "path relative to the project root"))
                .put("oldString", str("oldString", "the exact text to replace"))
                .put("newString", str("newString", "the replacement text"))
                .put("replaceAll", bool("replaceAll", "replace every occurrence")),
            listOf("filePath", "oldString", "newString"),
        )
        tool(
            "list",
            "List a directory of the project.",
            JSONObject().put("path", str("path", "directory path, '.' is the project root")),
            listOf("path"),
        )
        tool(
            "glob",
            "Find files by glob pattern (e.g. '**/*.py', 'src/**/*.js'), sorted by modification time.",
            JSONObject()
                .put("pattern", str("pattern", "glob pattern"))
                .put("path", str("path", "directory to search in, defaults to the project root")),
            listOf("pattern"),
        )
        tool(
            "grep",
            "Search file contents with a regular expression; returns 'path:line: text' matches.",
            JSONObject()
                .put("pattern", str("pattern", "regular expression"))
                .put("path", str("path", "directory to search in, defaults to the project root"))
                .put("include", str("include", "glob filter for file names, e.g. '*.py'")),
            listOf("pattern"),
        )
        tool(
            "apply_patch",
            "Apply a patch in the *** Begin Patch format with *** Add File / *** Update File (+- lines) / *** Move to / *** Delete File sections.",
            JSONObject().put("patchText", str("patchText", "the full patch text")),
            listOf("patchText"),
        )
        tool(
            "todowrite",
            "Create or update the session todo list. Replaces the whole list; statuses: pending, in_progress, completed.",
            JSONObject().put(
                "todos",
                arr(
                    "todos",
                    "the full todo list",
                    JSONObject().put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("id", str("id", "stable id"))
                                .put("content", str("content", "task text"))
                                .put("status", str("status", "pending | in_progress | completed")),
                        )
                        .put("required", JSONArray(listOf("content", "status"))),
                ),
            ),
            listOf("todos"),
        )
        tool(
            "webfetch",
            "Fetch an http(s) URL and return the page as plain text (HTML stripped), max ${MAX_WEB} chars.",
            JSONObject().put("url", str("url", "the URL to fetch")),
            listOf("url"),
        )
        tool(
            "skill",
            "Load a skill (a SKILL.md file) and return its full content. ${"\n"}${skills.availableSkillsXml(projectDir)}",
            JSONObject().put("name", str("name", "skill name to load")),
            listOf("name"),
        )
        tool(
            "question",
            "Ask the user questions with optional choices; use this to clarify requirements or offer directions.",
            JSONObject().put(
                "questions",
                arr(
                    "questions",
                    "one or more questions",
                    JSONObject().put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("header", str("header", "short header"))
                                .put("question", str("question", "the question text"))
                                .put("options", arr("options", "answer options", str("option", ""))),
                        )
                        .put("required", JSONArray(listOf("question"))),
                ),
            ),
            listOf("questions"),
        )
    }

    /** Executes one tool call; never throws — errors come back as text. */
    fun execute(name: String, argsJson: String): String {
        return try {
            val args = JSONObject(argsJson.ifBlank { "{}" })
            when (name) {
                "bash" -> doBash(args)
                "read" -> doRead(args)
                "write" -> doWrite(args)
                "edit" -> doEdit(args)
                "list" -> doList(args)
                "glob" -> doGlob(args)
                "grep" -> doGrep(args)
                "apply_patch" -> doApplyPatch(args)
                "todowrite" -> doTodoWrite(args)
                "webfetch" -> doWebFetch(args)
                "skill" -> doSkill(args)
                "question" -> doQuestion(args)
                else -> "unknown tool: $name"
            }
        } catch (t: Throwable) {
            "error: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    // ---------------------------------------------------------------- tools

    private fun base(): File = projectDir ?: workspace.root

    private fun doBash(args: JSONObject): String {
        val command = args.getString("command")
        val timeout = args.optLong("timeout", BASH_TIMEOUT_MS).coerceIn(1_000, BASH_TIMEOUT_MS)
        val r = shell.execute(command, base(), timeout)
        val out = buildString {
            append("exit code: ").append(r.exitCode).append('\n')
            if (r.output.isNotBlank()) append(r.output.trimEnd()).append('\n')
        }
        return out.trim().take(MAX_TOOL_OUTPUT)
    }

    private fun doRead(args: JSONObject): String {
        val f = resolve(args.getString("filePath")) ?: return "error: путь вне песочницы"
        if (!f.exists()) return "error: файл не найден: ${args.getString("filePath")}"
        if (f.isDirectory) return "error: это каталог, используйте list"
        val text = f.readText(Charsets.UTF_8)
        val offset = args.optInt("offset", 1).coerceAtLeast(1)
        val limit = args.optInt("limit", 0)
        val lines = text.lines()
        val from = (offset - 1).coerceIn(0, lines.size)
        val to = if (limit > 0) minOf(from + limit, lines.size) else lines.size
        val picked = lines.subList(from, to)
        val body = picked.joinToString("\n")
        return (body.take(MAX_READ) + if (body.length > MAX_READ) "\n… (обрезано)" else "")
            .ifEmpty { "(пустой файл)" }
    }

    private fun doWrite(args: JSONObject): String {
        val path = args.getString("filePath")
        if (path.isBlank()) return "error: пустой путь"
        val f = resolve(path) ?: return "error: путь вне песочницы"
        f.parentFile?.mkdirs()
        f.writeText(args.optString("content", ""), Charsets.UTF_8)
        return "wrote ${args.optString("content", "").length} chars to ${workspace.relativeTo(f)}"
    }

    private fun doEdit(args: JSONObject): String {
        val f = resolve(args.getString("filePath")) ?: return "error: путь вне песочницы"
        if (!f.isFile) return "error: файл не найден: ${args.getString("filePath")}"
        val old = args.getString("oldString")
        val new = args.optString("newString", "")
        if (old.isEmpty()) return "error: oldString пуст"
        val text = f.readText(Charsets.UTF_8)
        val count = countOccurrences(text, old)
        if (count == 0) return "error: oldString не найден в файле"
        if (count > 1 && !args.optBoolean("replaceAll", false)) {
            return "error: oldString встречается $count раз — уточните текст или передайте replaceAll: true"
        }
        val updated = if (args.optBoolean("replaceAll", false)) text.replace(old, new) else text.replaceFirst(old, new)
        f.writeText(updated, Charsets.UTF_8)
        return "ok: заменено вхождений: ${if (args.optBoolean("replaceAll", false)) count else 1}"
    }

    private fun doList(args: JSONObject): String {
        val path = args.optString("path", ".")
        val dir = resolve(path) ?: return "error: путь вне песочницы"
        if (!dir.exists()) return "error: нет такого каталога: $path"
        if (!dir.isDirectory) return "error: не каталог: $path"
        val entries = dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name }) ?: return "(empty)"
        if (entries.isEmpty()) return "(empty)"
        return entries.take(200).joinToString("\n") { f -> if (f.isDirectory) f.name + "/" else f.name + " (${f.length()} bytes)" }
    }

    private fun doGlob(args: JSONObject): String {
        val pattern = args.getString("pattern")
        val dir = resolve(args.optString("path", ".")) ?: return "error: путь вне песочницы"
        val base = dir.takeIf { it.isDirectory } ?: workspace.root
        val matches = mutableListOf<File>()
        base.walkTopDown().forEach { f ->
            if (f.isFile) {
                val rel = f.relativeToOrNull(base)?.path ?: return@forEach
                if (globToRegex(pattern).matches(rel)) matches += f
            }
        }
        if (matches.isEmpty()) return "(нет совпадений)"
        return matches.sortedByDescending { it.lastModified() }
            .take(200)
            .joinToString("\n") { workspace.relativeTo(it) }
    }

    private fun doGrep(args: JSONObject): String {
        val pattern = try { Regex(args.getString("pattern")) } catch (t: Throwable) { return "error: плохой regex: ${t.message}" }
        val dir = resolve(args.optString("path", ".")) ?: return "error: путь вне песочницы"
        val base = dir.takeIf { it.isDirectory } ?: workspace.root
        val include = args.optString("include").takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        val out = StringBuilder()
        base.walkTopDown().forEach { f ->
            if (out.length > MAX_TOOL_OUTPUT) return@forEach
            if (!f.isFile || f.length() > 512 * 1024) return@forEach
            val rel = workspace.relativeTo(f)
            if (include != null && !include.matches(f.name) && !include.matches(rel)) return@forEach
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
            text.lines().forEachIndexed { idx, line ->
                if (pattern.containsMatchIn(line)) out.append("$rel:${idx + 1}: ${line.take(300)}\n")
            }
        }
        return out.toString().trim().take(MAX_TOOL_OUTPUT).ifEmpty { "(нет совпадений)" }
    }

    private fun doApplyPatch(args: JSONObject): String {
        return try {
            val result = ApplyPatch.apply(args.getString("patchText")) { path -> resolve(path) }
            buildString {
                if (result.applied.isNotEmpty()) appendLine(result.applied.joinToString("\n"))
                if (result.skipped.isNotEmpty()) appendLine("skipped: " + result.skipped.joinToString("; "))
            }.trim().ifEmpty { "патч не содержал операций" }
        } catch (e: ApplyPatch.PatchException) {
            "error: ${e.message}"
        }
    }

    private fun doTodoWrite(args: JSONObject): String {
        val arr = args.optJSONArray("todos") ?: return "error: нужен массив todos"
        val list = mutableListOf<Sessions.Todo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val content = o.optString("content")
            if (content.isEmpty()) continue
            val status = o.optString("status", "pending")
            list += Sessions.Todo(
                id = o.optString("id").ifEmpty { "t$i" },
                content = content,
                status = if (status in listOf("pending", "in_progress", "completed")) status else "pending",
            )
        }
        todos = list
        onTodos?.invoke(list)
        return "todos updated: ${list.size}"
    }

    private fun doWebFetch(args: JSONObject): String {
        val url = args.getString("url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "error: только http(s) URL"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; PocketRunAgent/1.3)")
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) return "HTTP $status: ${body.take(300)}"
            val text = stripHtml(body)
            text.take(MAX_WEB).ifEmpty { "(пустой ответ)" }
        } catch (t: Throwable) {
            "error: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun doSkill(args: JSONObject): String {
        val name = args.getString("name")
        val skill = skills.load(name, projectDir)
            ?: return "error: навык '$name' не найден. Доступны: ${skills.discover(projectDir).joinToString(", ") { it.name }.ifEmpty { "(нет)" }}"
        return skill.content.take(32 * 1024)
    }

    private fun doQuestion(args: JSONObject): String {
        val arr = args.optJSONArray("questions") ?: return "error: нужен массив questions"
        val questions = mutableListOf<Question>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val opts = o.optJSONArray("options") ?: JSONArray()
            questions += Question(
                header = o.optString("header").takeIf { it.isNotEmpty() },
                question = o.optString("question").ifEmpty { "Вопрос" },
                options = (0 until opts.length()).map { opts.optString(it) },
            )
        }
        if (questions.isEmpty()) return "error: нет вопросов"
        val asker = questionAsker ?: return "(вопросы недоступны в этом режиме)"
        return asker(questions)
    }

    // ---------------------------------------------------------------- helpers

    private fun resolve(path: String): File? = workspace.resolve(path, base())

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            idx = text.indexOf(needle, idx)
            if (idx < 0) return count
            count++
            idx += needle.length
        }
    }

    private fun globToRegex(pattern: String): Regex {
        val regex = buildString {
            append('^')
            var i = 0
            while (i < pattern.length) {
                when (val c = pattern[i]) {
                    '*' -> if (i + 1 < pattern.length && pattern[i + 1] == '*') { append(".*"); i++ } else append("[^/]*")
                    '?' -> append('.')
                    else -> if (c in "\\^$.|+()[]{}") { append('\\'); append(c) } else append(c)
                }
                i++
            }
            append('$')
        }
        return Regex(regex)
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<!--.*?-->"), " ")
            .replace(Regex("(?s)<br[^>]*>|</p>|</div>|</li>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n")
            .trim()
    }
}
