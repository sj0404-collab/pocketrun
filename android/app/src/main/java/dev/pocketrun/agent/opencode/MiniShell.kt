package dev.pocketrun.agent.opencode

import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.RuntimeKind
import dev.pocketrun.runtime.js.JsRuntime
import dev.pocketrun.runtime.npm.NpxRuntime
import dev.pocketrun.runtime.python.PythonRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The `bash` tool of the opencode agent: a small POSIX-flavoured shell that
 * runs entirely inside the workspace sandbox. Supports quoting, `&&` chaining,
 * `|` pipes and `>` / `>>` redirection for the built-in commands, plus
 * `python`, `node` and `npx` routed to the real app runtimes.
 *
 * Anything it cannot do fails with a clear message instead of pretending —
 * the model is told up front (in the system prompt) which commands exist.
 */
class MiniShell(
    private val workspace: Workspace,
    private val jsRuntime: JsRuntime,
    private val npxRuntime: NpxRuntime,
) {

    companion object {
        const val MAX_OUTPUT = 32 * 1024
        const val MAX_FILE = 2 * 1024 * 1024
        val COMMANDS = listOf(
            "cat", "cd", "cp", "date", "echo", "env", "false", "find", "grep", "head",
            "ls", "mkdir", "mv", "node", "npm", "npx", "pwd", "python", "rm", "sleep", "tail",
            "touch", "true", "uname", "wc", "which", "help",
        )
        private val HELP = """
            Доступные команды: ${COMMANDS.joinToString(" ")}
            python <file.py> | python -c '<код>'   — CPython 3.13 (стандартная библиотека)
            node <file.js>  | node -e '<код>'      — Node-совместимый слой (без class/async/import)
            npx <пакет> [аргументы]               — чистые JS npm-пакеты
            npm init -y | npm install <пакет> | npm run <script> | npm ls
                                                   — управление node-проектом (install качает
                                                     чистые JS-пакеты; require() их видит)
            Поддерживаются кавычки, && , | и > >> для встроенных команд.
        """.trimIndent()
    }

    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /** Executes [commandLine] with [cwd] as the working directory. */
    fun execute(commandLine: String, cwd: File, timeoutMs: Long = 120_000): Result {
        var dir = if (cwd.isDirectory) cwd else workspace.root
        val segments = splitAnd(commandLine)
        if (segments.isEmpty()) return Result(0, "")
        val out = StringBuilder()
        var lastExit = 0
        for (segment in segments) {
            if (lastExit != 0) break
            val r = runPipeline(segment, dir, timeoutMs) { d -> dir = d }
            out.append(r.output)
            lastExit = r.exitCode
        }
        return Result(lastExit, cap(out.toString()))
    }

    // ---------------------------------------------------------------- structure

    /** Splits on && at top level (outside quotes). */
    private fun splitAnd(line: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null -> {
                    cur.append(c)
                    if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> { quote = c; cur.append(c) }
                c == '&' && i + 1 < line.length && line[i + 1] == '&' -> {
                    if (cur.isNotBlank()) parts += cur.toString().trim()
                    cur.clear()
                    i++
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotBlank()) parts += cur.toString().trim()
        return parts
    }

    /** Runs one `cmd | cmd | cmd [> file]` pipeline segment. */
    private fun runPipeline(
        segment: String,
        dir: File,
        timeoutMs: Long,
        onCd: (File) -> Unit,
    ): Result {
        // split on top-level |
        val stages = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            when {
                quote != null -> { cur.append(c); if (c == quote) quote = null }
                c == '"' || c == '\'' -> { quote = c; cur.append(c) }
                c == '|' -> { stages += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotBlank()) stages += cur.toString().trim()
        if (stages.isEmpty()) return Result(0, "")

        var input = ""
        var exit = 0
        var redirectFile: File? = null
        var append = false

        for ((index, stage) in stages.withIndex()) {
            val (tokens, redir, redirAppend) = extractRedirect(tokenize(stage), dir)
            redirectFile = redir
            append = redirAppend
            if (tokens.isEmpty()) continue
            val cmd = tokens[0]
            val args = tokens.drop(1)

            // runtime commands ignore stdin/pipes — they run to completion
            val r = when (cmd) {
                "python", "python3" -> runPython(args, dir, timeoutMs)
                "node" -> runNode(args, dir, timeoutMs)
                "npx" -> runNpx(args, dir, timeoutMs)
            "npm" -> npm(args, dir, timeoutMs)
                else -> runBuiltin(cmd, args, dir, input, onCd)
            }
            exit = r.exitCode
            input = r.output
            // redirect only applies to the last stage
            if (index == stages.lastIndex) {
                if (redirectFile != null) {
                    writeToFile(redirectFile, input, append)
                    return Result(r.exitCode, "")
                }
            }
        }
        return Result(exit, input)
    }

    /** `> file` / `>> file` on built-in commands. */
    private fun extractRedirect(tokens: List<String>, dir: File): Triple<List<String>, File?, Boolean> {
        val gt = tokens.indexOfFirst { it == ">" || it == ">>" }
        if (gt < 0 || gt + 1 >= tokens.size) return Triple(tokens, null, false)
        val file = sandboxPath(tokens[gt + 1], dir)
        return Triple(tokens.subList(0, gt), file, tokens[gt] == ">>")
    }

    // ---------------------------------------------------------------- builtins

    private fun runBuiltin(cmd: String, args: List<String>, dir: File, stdin: String, onCd: (File) -> Unit): Result {
        return when (cmd) {
            "pwd" -> Result(0, dir.relativeToOrNull(workspace.root)?.let { "./$it" } ?: dir.absolutePath + "\n")
            "cd" -> {
                val target = sandboxPath(args.firstOrNull() ?: ".", dir)
                if (target == null || !target.isDirectory) Result(1, "cd: ${args.firstOrNull() ?: ""}: нет такого каталога\n")
                else { onCd(target); Result(0, "") }
            }
            "ls" -> ls(args, dir)
            "cat" -> cat(args, dir, stdin)
            "echo" -> Result(0, args.joinToString(" ") + "\n")
            "mkdir" -> mkdir(args, dir)
            "rm" -> rm(args, dir)
            "mv" -> mv(args, dir)
            "cp" -> cp(args, dir)
            "touch" -> {
                var ok = 0
                for (a in flagsFree(args)) {
                    val f = sandboxPath(a, dir)
                    if (f == null) { ok = 1; continue }
                    if (f.exists()) f.setLastModified(System.currentTimeMillis()) else f.createNewFile()
                }
                Result(ok, "")
            }
            "head" -> headTail(args, dir, fromStart = true)
            "tail" -> headTail(args, dir, fromStart = false)
            "wc" -> wc(args, dir, stdin)
            "find" -> find(args, dir)
            "grep" -> shellGrep(args, dir, stdin)
            "env" -> Result(0, "HOME=${workspace.root.absolutePath}\nLANG=C.UTF-8\n")
            "date" -> Result(0, SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.US).format(Date()) + "\n")
            "sleep" -> {
                val sec = args.firstOrNull()?.toFloatOrNull() ?: return Result(1, "sleep: нужен аргумент в секундах\n")
                if (sec > 30f) return Result(1, "sleep: максимум 30 секунд\n")
                Thread.sleep((sec * 1000).toLong())
                Result(0, "")
            }
            "uname" -> Result(0, "Linux aarch64 PocketRun\n")
            "which" -> Result(if (args.firstOrNull() in COMMANDS) 0 else 1, if (args.firstOrNull() in COMMANDS) "/bin/${args.first()}\n" else "")
            "true" -> Result(0, "")
            "false" -> Result(1, "")
            "help" -> Result(0, HELP + "\n")
            else -> Result(127, "bash: $cmd: команда не найдена (доступно: ${COMMANDS.joinToString(", ")})\n")
        }
    }

    private fun flagsFree(args: List<String>): List<String> = args.filter { !it.startsWith("-") }

    private fun ls(args: List<String>, dir: File): Result {
        val all = args.any { it == "-a" || it == "-la" || it == "-al" }
        val long = args.any { it == "-l" || it == "-la" || it == "-al" || it == "-lh" }
        val targets = flagsFree(args).ifEmpty { listOf(".") }
        val out = StringBuilder()
        var exit = 0
        for (t in targets) {
            val f = sandboxPath(t, dir)
            if (f == null || !f.exists()) { exit = 1; out.append("ls: $t: нет такого файла\n"); continue }
            val entries = when {
                f.isDirectory -> f.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
                else -> listOf(f)
            }.filter { all || !it.name.startsWith(".") }
            if (targets.size > 1) out.append("${t}:\n")
            for (e in entries) {
                if (long) {
                    out.append("%s %8d  %s\n".format(if (e.isDirectory()) "d" else "-", e.length(), e.name))
                } else {
                    out.append(if (e.isDirectory()) e.name + "/" else e.name)
                    out.append("\n")
                }
            }
            if (targets.size > 1) out.append("\n")
        }
        return Result(exit, out.toString())
    }

    private fun cat(args: List<String>, dir: File, stdin: String): Result {
        if (args.isEmpty() || args.first() == "-") return Result(0, stdin)
        val out = StringBuilder()
        var exit = 0
        for (a in args) {
            val f = sandboxPath(a, dir)
            if (f == null || !f.isFile) { exit = 1; out.append("cat: $a: нет такого файла\n"); continue }
            if (f.length() > MAX_FILE) { exit = 1; out.append("cat: $a: файл слишком большой\n"); continue }
            out.append(f.readText(Charsets.UTF_8))
        }
        return Result(exit, out.toString())
    }

    private fun mkdir(args: List<String>, dir: File): Result {
        val recursive = args.any { it == "-p" }
        var exit = 0
        val out = StringBuilder()
        for (a in flagsFree(args)) {
            val f = sandboxPath(a, dir)
            if (f == null) { exit = 1; continue }
            if (f.exists()) {
                if (!recursive) { exit = 1; out.append("mkdir: $a: уже существует\n") }
                continue
            }
            if (recursive) f.mkdirs() else f.mkdir()
            if (!f.isDirectory) { exit = 1; out.append("mkdir: $a: не удалось создать\n") }
        }
        return Result(exit, out.toString())
    }

    private fun rm(args: List<String>, dir: File): Result {
        val recursive = args.any { it == "-r" || it == "-rf" || it == "-fr" || it == "-R" }
        var exit = 0
        val out = StringBuilder()
        for (a in flagsFree(args)) {
            val f = sandboxPath(a, dir)
            if (f == null || !f.exists()) { exit = 1; out.append("rm: $a: нет такого файла\n"); continue }
            if (f.isDirectory && !recursive) { exit = 1; out.append("rm: $a: это каталог (нужен -r)\n"); continue }
            if (!f.deleteRecursively()) { exit = 1; out.append("rm: $a: не удалось удалить\n") }
        }
        return Result(exit, out.toString())
    }

    private fun mv(args: List<String>, dir: File): Result {
        val files = flagsFree(args)
        if (files.size < 2) return Result(1, "mv: использование: mv <откуда> <куда>\n")
        val from = sandboxPath(files[0], dir) ?: return Result(1, "mv: путь вне песочницы\n")
        val to = sandboxPath(files[1], dir) ?: return Result(1, "mv: путь вне песочницы\n")
        if (!from.exists()) return Result(1, "mv: ${files[0]}: нет такого файла\n")
        val target = if (to.isDirectory) File(to, from.name) else to
        target.parentFile?.mkdirs()
        return if (from.renameTo(target)) Result(0, "") else Result(1, "mv: не удалось переместить\n")
    }

    private fun cp(args: List<String>, dir: File): Result {
        val files = flagsFree(args)
        if (files.size < 2) return Result(1, "cp: использование: cp <откуда> <куда>\n")
        val from = sandboxPath(files[0], dir) ?: return Result(1, "cp: путь вне песочницы\n")
        val to = sandboxPath(files[1], dir) ?: return Result(1, "cp: путь вне песочницы\n")
        if (!from.exists()) return Result(1, "cp: ${files[0]}: нет такого файла\n")
        val target = if (to.isDirectory) File(to, from.name) else to
        return try {
            if (from.isDirectory) from.copyRecursively(target, overwrite = true)
            else { target.parentFile?.mkdirs(); from.copyTo(target, overwrite = true) }
            Result(0, "")
        } catch (t: Throwable) { Result(1, "cp: ${t.message}\n") }
    }

    private fun headTail(args: List<String>, dir: File, fromStart: Boolean): Result {
        // head [-n N] [-N] file — `head -n 5 f`, `head -5 f`, `head f` all work.
        var n = 10
        val files = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "-n" && i + 1 < args.size && args[i + 1].toIntOrNull() != null ->
                    n = args[++i].toInt()
                args[i].startsWith("-") && args[i].removePrefix("-").toIntOrNull() != null ->
                    n = args[i].removePrefix("-").toInt()
                !args[i].startsWith("-") -> files += args[i]
            }
            i++
        }
        if (files.isEmpty()) return Result(1, "${if (fromStart) "head" else "tail"}: нужен файл\n")
        val f = sandboxPath(files[0], dir) ?: return Result(1, "путь вне песочницы\n")
        if (!f.isFile) return Result(1, "${files[0]}: нет такого файла\n")
        val lines = f.readText(Charsets.UTF_8).lines()
        val picked = if (fromStart) lines.take(n) else lines.takeLast(n)
        return Result(0, picked.joinToString("\n") + "\n")
    }

    private fun wc(args: List<String>, dir: File, stdin: String): Result {
        val files = flagsFree(args)
        val text = when {
            files.isEmpty() -> stdin
            else -> sandboxPath(files.last(), dir)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
                ?: return Result(1, "wc: ${files.last()}: нет такого файла\n")
        }
        // A trailing newline does not start a new line (like wc -l).
        val lines = text.lines().let { if (text.endsWith("\n")) it.dropLast(1) else it }
        return Result(0, "%7d %7d %7d\n".format(lines.size, text.split(Regex("\\s+")).count { it.isNotEmpty() }, text.length))
    }

    private fun find(args: List<String>, dir: File): Result {
        // find [path] [-name pattern] [-type f|d]
        var start = dir
        var namePattern: String? = null
        var type: Char? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-name" -> { if (i + 1 < args.size) namePattern = args[++i] }
                "-type" -> { if (i + 1 < args.size) type = args[++i].firstOrNull() }
                else -> if (!args[i].startsWith("-")) start = sandboxPath(args[i], dir) ?: start
            }
            i++
        }
        val out = StringBuilder()
        start.walkTopDown().forEach { f ->
            if (out.length > MAX_OUTPUT) return@forEach
            if (type == 'f' && !f.isFile) return@forEach
            if (type == 'd' && !f.isDirectory) return@forEach
            val rel = f.relativeToOrNull(start) ?: return@forEach
            val path = if (rel.path == ".") start.name else start.name + "/" + rel.path
            if (namePattern == null || globMatch(namePattern, rel.path) || globMatch(namePattern, f.name)) {
                out.append(path).append('\n')
            }
        }
        return Result(0, out.toString())
    }

    private fun shellGrep(args: List<String>, dir: File, stdin: String): Result {
        val ignoreCase = args.any { it == "-i" }
        val rest = args.filter { it != "-i" && it != "-n" && !it.startsWith("-") }
        if (rest.isEmpty()) return Result(1, "grep: использование: grep [-i] <шаблон> [файл]\n")
        val pattern = try {
            if (ignoreCase) Regex(rest[0], RegexOption.IGNORE_CASE) else Regex(rest[0])
        } catch (t: Throwable) {
            return Result(2, "grep: плохой regex: ${t.message}\n")
        }
        val text = if (rest.size > 1) {
            sandboxPath(rest[1], dir)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
                ?: return Result(1, "grep: ${rest[1]}: нет такого файла\n")
        } else stdin
        val out = StringBuilder()
        text.lines().forEachIndexed { idx, line ->
            if (pattern.containsMatchIn(line)) out.append("%d:%s\n".format(idx + 1, line))
        }
        return Result(if (out.isEmpty()) 1 else 0, out.toString())
    }

    // ---------------------------------------------------------------- runtimes

    private fun runPython(args: List<String>, dir: File, timeoutMs: Long): Result {
        if (!PythonRuntime.isAvailable()) {
            return Result(1, "python: рантайм ещё запускается, попробуйте снова через пару секунд\n")
        }
        return when {
            args.firstOrNull() == "-c" && args.size >= 2 -> runCodeFile("py", args[1], dir, timeoutMs, python = true)
            args.isEmpty() -> Result(1, "python: нужен файл или -c '<код>'\n")
            else -> {
                val f = sandboxPath(args[0], dir)
                    ?: return Result(1, "python: путь вне песочницы\n")
                if (!f.isFile) return Result(1, "python: ${args[0]}: нет такого файла\n")
                val r = PythonRuntime.executeSync(
                    ExecRequest(RuntimeKind.PYTHON, f.absolutePath, args.drop(1), dir),
                    timeoutMs,
                )
                Result(r.exitCode, r.stdout + (if (r.stderr.isNotBlank()) r.stderr else ""))
            }
        }
    }

    private fun runNode(args: List<String>, dir: File, timeoutMs: Long): Result {
        return when {
            args.firstOrNull() == "-e" && args.size >= 2 -> runCodeFile("js", args[1], dir, timeoutMs, python = false)
            args.isEmpty() -> Result(1, "node: нужен файл или -e '<код>'\n")
            else -> {
                val f = sandboxPath(args[0], dir)
                    ?: return Result(1, "node: путь вне песочницы\n")
                if (!f.isFile) return Result(1, "node: ${args[0]}: нет такого файла\n")
                val r = jsRuntime.executeSync(
                    ExecRequest(RuntimeKind.NODE, f.absolutePath, args.drop(1), dir),
                    timeoutMs,
                )
                Result(r.exitCode, r.stdout + (if (r.stderr.isNotBlank()) r.stderr else ""))
            }
        }
    }

    private fun runNpx(args: List<String>, dir: File, timeoutMs: Long): Result {
        val spec = args.firstOrNull() ?: return Result(1, "npx: укажите пакет\n")
        val r = npxRuntime.executeSync(
            ExecRequest(RuntimeKind.NPX, spec, args.drop(1), dir),
            timeoutMs,
        )
        return Result(r.exitCode, r.stdout + (if (r.stderr.isNotBlank()) r.stderr else ""))
    }

    // ---------------------------------------------------------------- npm

    /**
     * A minimal `npm` for the sandbox: init creates package.json, install
     * downloads pure-JS packages (into the shared store, which require()
     * already resolves), run executes package.json scripts through this shell.
     */
    private fun npm(args: List<String>, dir: File, timeoutMs: Long): Result {
        val sub = args.firstOrNull() ?: return Result(1, "npm: нужна подкоманда: init, install, run, test, ls\n")
        val rest = args.drop(1)
        return try {
            when (sub) {
                "init" -> npmInit(rest, dir)
                "install", "i", "add" -> npmInstall(rest, dir)
                "run" -> npmRun(rest, dir, timeoutMs)
                "test" -> npmRun(listOf("test"), dir, timeoutMs)
                "ls", "list" -> npmLs(dir)
                "--version", "-v" -> Result(0, "npm (PocketRun) — init / install / run / test / ls\n")
                else -> Result(1, "npm: неизвестная подкоманда '$sub' (есть: init, install, run, test, ls)\n")
            }
        } catch (t: Throwable) {
            Result(1, "npm: ${t.message ?: t.javaClass.simpleName}\n")
        }
    }

    private fun npmInit(args: List<String>, dir: File): Result {
        val pkg = File(dir, "package.json")
        if (pkg.isFile && args.none { it == "-y" || it == "--yes" || it == "-f" || it == "--force" }) {
            return Result(1, "npm: package.json уже существует (npm init -y чтобы перезаписать)\n")
        }
        val json = org.json.JSONObject()
            .put("name", dir.name.replace(Regex("[^a-z0-9-]"), "-").trim('-').ifEmpty { "project" })
            .put("version", "1.0.0")
            .put("description", "")
            .put("main", "index.js")
            .put(
                "scripts",
                org.json.JSONObject().put("test", "echo \"Error: no test specified\" && exit 1"),
            )
        pkg.writeText(json.toString(2) + "\n", Charsets.UTF_8)
        return Result(0, "создан package.json (${json.optString("name")})\n")
    }

    private fun npmInstall(args: List<String>, dir: File): Result {
        val pkg = File(dir, "package.json")
        val manifest = if (pkg.isFile) org.json.JSONObject(pkg.readText(Charsets.UTF_8)) else org.json.JSONObject()
        val specs = args.filter { !it.startsWith("-") }
        val toInstall: List<Pair<String, String?>> = if (specs.isEmpty()) {
            // no arguments: install everything from package.json dependencies
            val deps = manifest.optJSONObject("dependencies")
                ?: return Result(1, "npm: нет пакетов для установки и нет dependencies в package.json\n")
            deps.keys().asSequence().map { it to deps.optString(it).takeIf { r -> r != "*" } }.toList()
        } else {
            specs.mapNotNull { raw ->
                NpxRuntime.parseSpec(raw)?.let { it.name to it.version }
            }.ifEmpty { return Result(1, "npm: не удалось разобрать имена пакетов\n") }
        }
        val out = StringBuilder()
        for ((name, version) in toInstall) {
            val installed = npxRuntime.install(
                NpxRuntime.PackageSpec(name, version),
                log = { line -> out.append(line).append('\n') },
            )
            val actual = org.json.JSONObject(File(installed, "package.json").readText(Charsets.UTF_8)).optString("version")
            val deps = manifest.optJSONObject("dependencies") ?: org.json.JSONObject().also { manifest.put("dependencies", it) }
            deps.put(name, actual)
            out.append("✓ ").append(name).append('@').append(actual).append('\n')
        }
        if (pkg.isFile || toInstall.isNotEmpty()) {
            manifest.put("dependencies", manifest.optJSONObject("dependencies") ?: org.json.JSONObject())
            pkg.writeText(manifest.toString(2) + "\n", Charsets.UTF_8)
        }
        return Result(0, cap(out.toString()))
    }

    private fun npmRun(args: List<String>, dir: File, timeoutMs: Long): Result {
        val pkg = File(dir, "package.json")
        if (!pkg.isFile) return Result(1, "npm run: нет package.json в текущем каталоге\n")
        val manifest = org.json.JSONObject(pkg.readText(Charsets.UTF_8))
        val scripts = manifest.optJSONObject("scripts")
            ?: return Result(1, "npm run: в package.json нет секции scripts\n")
        val script = args.firstOrNull()
            ?: return Result(0, "доступные скрипты:\n" + scripts.keys().asSequence().joinToString("\n") { "  $it" } + "\n")
        val cmd = scripts.optString(script).takeIf { it.isNotEmpty() }
            ?: return Result(1, "npm run: нет скрипта \"$script\" (есть: ${scripts.keys().asSequence().joinToString(", ")})\n")
        val r = execute(cmd, dir, timeoutMs)
        return Result(r.exitCode, r.output)
    }

    private fun npmLs(dir: File): Result {
        val out = StringBuilder()
        val local = File(dir, "node_modules").takeIf { it.isDirectory }
        if (local != null) {
            out.append("локальные (node_modules):\n")
            local.listFiles()?.sortedBy { it.name }?.forEach { out.append("  ${it.name}\n") }
        }
        out.append("установленные пакеты (общий стор, виден из require):\n")
        npxRuntime.installedPackages().forEach { (n, v) -> out.append("  $n@$v\n") }
        return Result(0, out.toString().ifEmpty { "(ничего не установлено)\n" })
    }

    private fun runCodeFile(ext: String, code: String, dir: File, timeoutMs: Long, python: Boolean): Result {
        val scratch = File(workspace.cache, "opencode-shell").apply { mkdirs() }
        val f = File(scratch, "cmd.${if (python) "py" else "js"}")
        f.writeText(code, Charsets.UTF_8)
        val r = if (python) {
            if (!PythonRuntime.isAvailable()) return Result(1, "python: рантайм ещё запускается\n")
            PythonRuntime.executeSync(ExecRequest(RuntimeKind.PYTHON, f.absolutePath, emptyList(), dir), timeoutMs)
        } else {
            jsRuntime.executeSync(ExecRequest(RuntimeKind.NODE, f.absolutePath, emptyList(), dir), timeoutMs)
        }
        return Result(r.exitCode, r.stdout + (if (r.stderr.isNotBlank()) r.stderr else ""))
    }

    // ---------------------------------------------------------------- helpers

    private fun writeToFile(f: File, content: String, append: Boolean) {
        try {
            f.parentFile?.mkdirs()
            if (append) f.appendText(content, Charsets.UTF_8) else f.writeText(content, Charsets.UTF_8)
        } catch (_: Exception) { }
    }

    private fun sandboxPath(p: String, dir: File): File? = workspace.resolve(p, dir)

    /** Shell tokenizer: single/double quotes keep groups together. */
    internal fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var has = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null -> {
                    if (c == quote) quote = null else cur.append(c)
                }
                c == '"' || c == '\'' -> { quote = c; has = true }
                c.isWhitespace() -> {
                    if (cur.isNotEmpty() || has) { out += cur.toString(); cur.clear(); has = false }
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty() || has) out += cur.toString()
        return out
    }

    /** Glob match with `*`, `?` and `**` support. */
    internal fun globMatch(pattern: String, path: String): Boolean {
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
        return Regex(regex).matches(path)
    }

    private fun cap(text: String): String =
        if (text.length <= MAX_OUTPUT) text else text.take(MAX_OUTPUT) + "\n… (обрезано)"
}
