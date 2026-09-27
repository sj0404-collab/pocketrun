package dev.pocketrun.agent

import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.RuntimeKind
import dev.pocketrun.runtime.js.JsRuntime
import dev.pocketrun.runtime.npm.NpxRuntime
import dev.pocketrun.runtime.python.PythonRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The agent's toolbox: workspace files plus three ways to run code. Every tool
 * returns a plain string (capped) that becomes the `tool` message content; a
 * failing tool reports its error instead of throwing so the model can react.
 */
class AgentTools(
    private val workspace: Workspace,
    private val jsRuntime: JsRuntime,
    private val npxRuntime: NpxRuntime,
) {

    companion object {
        private const val MAX_READ = 32 * 1024
        private const val MAX_TOOL_OUTPUT = 12 * 1024
        private const val MAX_RUN_MS = 60_000L
        private const val MAX_NPX_MS = 180_000L
        private const val MAX_LIST = 200
        private const val MAX_SEARCH_HITS = 50
        private const val MAX_SEARCH_FILE = 512 * 1024
    }

    private val scratchDir: File get() = File(workspace.cache, "agent-scratch").apply { mkdirs() }

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

        tool(
            "list_projects",
            "List the user's projects (name plus number of files) and the npm packages installed in the sandbox.",
            JSONObject(),
            emptyList(),
        )
        tool(
            "list_dir",
            "List a directory of the workspace. Paths are relative to the workspace root; '.' is the root.",
            JSONObject().put("path", JSONObject().put("type", "string").put("description", "directory path, e.g. 'projects/hello'")),
            listOf("path"),
        )
        tool(
            "read_file",
            "Read a text file from the workspace (max 32 KB).",
            JSONObject().put("path", JSONObject().put("type", "string").put("description", "file path relative to the workspace root")),
            listOf("path"),
        )
        tool(
            "write_file",
            "Create or overwrite a text file in the workspace. Parent directories are created automatically.",
            JSONObject()
                .put("path", JSONObject().put("type", "string").put("description", "file path relative to the workspace root"))
                .put("content", JSONObject().put("type", "string").put("description", "full file content (UTF-8 text)")),
            listOf("path", "content"),
        )
        tool(
            "run_python",
            "Run Python 3.13 code (no pip packages; standard library only). stdout/stderr and the exit code are returned. Timeout 60 s.",
            JSONObject().put("code", JSONObject().put("type", "string").put("description", "Python source code to execute")),
            listOf("code"),
        )
        tool(
            "run_node",
            "Run JavaScript (Node-compatible subset on Rhino: require/fs/timers/Buffer/http work; no async/await, no native addons). Timeout 60 s.",
            JSONObject().put("code", JSONObject().put("type", "string").put("description", "JavaScript source code to execute")),
            listOf("code"),
        )
        tool(
            "run_npx",
            "Install (if needed) and run a pure-JavaScript npm package's CLI, like `npx <package> [args...]`. Only packages without native addons work. Timeout 180 s.",
            JSONObject()
                .put(
                    "package",
                    JSONObject().put("type", "string")
                        .put("description", "package name with optional version, e.g. 'cowsay' or 'semver@7'"),
                )
                .put(
                    "args",
                    JSONObject().put("type", "array")
                        .put("items", JSONObject().put("type", "string"))
                        .put("description", "command-line arguments"),
                ),
            listOf("package"),
        )
        tool(
            "search_files",
            "Search the workspace for files whose name contains the query, or whose text content contains it (case-insensitive).",
            JSONObject().put("query", JSONObject().put("type", "string").put("description", "substring to look for")),
            listOf("query"),
        )
    }

    /** Executes one tool call; never throws — errors come back as text. */
    fun execute(name: String, argsJson: String): String {
        return try {
            val args = JSONObject(argsJson.ifBlank { "{}" })
            when (name) {
                "list_projects" -> listProjects()
                "list_dir" -> listDir(args.optString("path", "."))
                "read_file" -> readFile(args.getString("path"))
                "write_file" -> writeFile(args.getString("path"), args.optString("content", ""))
                "run_python" -> runPython(args.getString("code"))
                "run_node" -> runNode(args.getString("code"))
                "run_npx" -> runNpx(args.getString("package"), args.optJSONArray("args"))
                "search_files" -> searchFiles(args.getString("query"))
                else -> "unknown tool: $name"
            }
        } catch (t: Throwable) {
            "error: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    // ---------------------------------------------------------------- files

    private fun listProjects(): String {
        val projects = workspace.projects.listFiles { it.isDirectory }.orEmpty()
            .map { "${it.name}/ (${it.walkTopDown().count()} files)" }
        val packages = npxRuntime.installedPackages().map { (n, v) -> "$n@$v" }
        return buildString {
            appendLine("projects:")
            if (projects.isEmpty()) appendLine("  (none)")
            projects.sorted().forEach { appendLine("  $it") }
            appendLine("packages:")
            if (packages.isEmpty()) appendLine("  (none)")
            packages.forEach { appendLine("  $it") }
        }.trim()
    }

    private fun listDir(path: String): String {
        val dir = workspace.resolve(path) ?: return "error: path rejected (outside sandbox)"
        if (!dir.exists()) return "error: no such directory: $path"
        if (!dir.isDirectory) return "error: not a directory: $path"
        val entries = dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name }) ?: return "(empty)"
        if (entries.isEmpty()) return "(empty)"
        return entries.take(MAX_LIST).joinToString("\n") { f ->
            if (f.isDirectory) "${f.name}/" else "${f.name} (${f.length()} bytes)"
        } + if (entries.size > MAX_LIST) "\n… and ${entries.size - MAX_LIST} more" else ""
    }

    private fun readFile(path: String): String {
        val f = workspace.resolve(path) ?: return "error: path rejected (outside sandbox)"
        if (!f.exists()) return "error: no such file: $path"
        if (f.isDirectory) return "error: is a directory: $path (use list_dir)"
        val text = f.readText(Charsets.UTF_8)
        if (text.length > MAX_READ) return text.take(MAX_READ) + "\n… (truncated, ${text.length} chars total)"
        return text
    }

    private fun writeFile(path: String, content: String): String {
        if (path.isBlank()) return "error: path is empty"
        val f = workspace.resolve(path) ?: return "error: path rejected (outside sandbox)"
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        return "wrote ${content.length} chars to ${workspace.relativeTo(f)}"
    }

    private fun searchFiles(query: String): String {
        if (query.isBlank()) return "error: empty query"
        val q = query.lowercase()
        val hits = mutableListOf<String>()
        workspace.root.walkTopDown().forEach { f ->
            if (hits.size >= MAX_SEARCH_HITS) return@forEach
            if (f.name.lowercase().contains(q)) {
                hits += "name: ${workspace.relativeTo(f)}"
            } else if (f.isFile && f.length() in 1..MAX_SEARCH_FILE) {
                val lower = runCatching { f.readText(Charsets.UTF_8).lowercase() }.getOrNull() ?: return@forEach
                val idx = lower.indexOf(q)
                if (idx >= 0) {
                    val from = (idx - 40).coerceAtLeast(0)
                    val snippet = lower.substring(from, (idx + q.length + 40).coerceAtMost(lower.length))
                        .replace('\n', ' ')
                    hits += "content: ${workspace.relativeTo(f)} …$snippet…"
                }
            }
        }
        if (hits.isEmpty()) return "no matches for '$query'"
        return hits.take(MAX_SEARCH_HITS).joinToString("\n")
    }

    // ---------------------------------------------------------------- run

    private fun runPython(code: String): String {
        val file = File(scratchDir, "agent.py")
        file.writeText(code, Charsets.UTF_8)
        if (!PythonRuntime.isAvailable()) return "error: Python runtime is still starting, try again in a moment"
        val result = PythonRuntime.executeSync(
            ExecRequest(RuntimeKind.PYTHON, file.absolutePath, emptyList(), scratchDir),
            MAX_RUN_MS,
        )
        return formatRun(result)
    }

    private fun runNode(code: String): String {
        val file = File(scratchDir, "agent.js")
        file.writeText(code, Charsets.UTF_8)
        val result = jsRuntime.executeSync(
            ExecRequest(RuntimeKind.NODE, file.absolutePath, emptyList(), scratchDir),
            MAX_RUN_MS,
        )
        return formatRun(result)
    }

    private fun runNpx(packageSpec: String, args: JSONArray?): String {
        val cliArgs = mutableListOf<String>()
        if (args != null) for (i in 0 until args.length()) cliArgs += args.optString(i, "")
        val result = npxRuntime.executeSync(
            ExecRequest(RuntimeKind.NPX, packageSpec, cliArgs, workspace.root),
            MAX_NPX_MS,
        )
        return formatRun(result)
    }

    private fun formatRun(result: dev.pocketrun.runtime.ExecResult): String {
        val out = result.stdout.trim()
        val err = result.stderr.trim()
        return buildString {
            appendLine("exit code: ${result.exitCode} (${result.durationMs} ms)")
            if (out.isNotEmpty()) appendLine("--- stdout ---").appendLine(out)
            if (err.isNotEmpty()) appendLine("--- stderr ---").appendLine(err)
        }.trim().take(MAX_TOOL_OUTPUT)
    }
}
