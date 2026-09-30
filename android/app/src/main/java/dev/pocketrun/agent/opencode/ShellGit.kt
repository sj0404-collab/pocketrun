package dev.pocketrun.agent.opencode

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * A small local git: enough to record what happened, inspect it, and undo it.
 *
 * Not a git implementation - no objects graph, no merges, no rebase, no
 * remotes. What it does is the set of commands an agent reaches for when it is
 * editing a project and wants a checkpoint or wants to see its own diff:
 * init, status, add, commit, log, show, diff, branch, checkout, restore, reset.
 *
 * History is one JSON file per commit under .git/pr-history, which keeps the
 * whole thing readable and impossible to corrupt halfway. Remotes and push are
 * deliberately absent: the `github` tool already does that properly with a
 * token, and two ways to do it would only drift.
 */
internal object ShellGit {

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())


    fun run(name: String, args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result? =
        when (name) {
            "git" -> git(args, dir, resolve)
            else -> null
        }

    private fun git(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        if (args.isEmpty()) return MiniShell.Result(1, "git: нужен подкоманда (init, status, add, commit, log, show, diff, branch, checkout, restore)\n")
        val sub = args[0]
        val rest = args.drop(1)
        return when (sub) {
            "init" -> init(dir)
            "status" -> status(dir)
            "add" -> add(rest, dir, resolve)
            "commit" -> commit(rest, dir, resolve)
            "log" -> log(rest, dir)
            "show" -> show(rest, dir)
            "diff" -> diff(rest, dir, resolve)
            "branch" -> branch(rest, dir)
            "checkout" -> checkout(rest, dir)
            "restore" -> restore(rest, dir, resolve)
            "reset" -> reset(rest, dir)
            "config" -> MiniShell.Result(0, "")
            "rev-parse" -> revParse(rest, dir)
            else -> MiniShell.Result(129, "git: '${sub}' не поддерживается здесь (есть: init, status, add, commit, log, show, diff, branch, checkout, restore, reset). Для GitHub используй инструмент github.\n")
        }
    }

    // ---------------------------------------------------------------- storage

    private fun gitDir(dir: File): File = File(dir, ".git")

    private fun historyFile(dir: File): File = File(gitDir(dir), "pr-history")

    private fun indexFile(dir: File): File = File(gitDir(dir), "pr-index")

    private fun headFile(dir: File): File = File(gitDir(dir), "pr-head")

    private class Commit(
        val id: String,
        val parent: String?,
        val message: String,
        val time: Long,
        val branch: String,
        val staged: Map<String, String>, // path -> sha256 of the content at commit time
        val stagedNew: Set<String>,
    )

    private fun readCommits(dir: File): List<Commit> {
        val f = historyFile(dir)
        if (!f.isFile) return emptyList()
        val out = mutableListOf<Commit>()
        for (block in f.readText(Charsets.UTF_8).split("\n\n")) {
            if (block.isBlank()) continue
            val map = HashMap<String, String>()
            for (line in block.lines()) {
                val s = line.indexOf(' ')
                if (s <= 0) continue
                map[line.take(s)] = line.substring(s + 1)
            }
            val staged = map["files"]?.split('\u0000')?.filter { it.isNotBlank() }?.associate {
                val h = it.indexOf(':')
                if (h <= 0) it to "" else it.take(h) to it.substring(h + 1)
            } ?: emptyMap()
            out += Commit(
                id = map["commit"] ?: return out,
                parent = map["parent"]?.takeIf { it.isNotBlank() },
                message = map["message"].orEmpty(),
                time = map["time"]?.toLongOrNull() ?: 0L,
                branch = map["branch"] ?: "main",
                staged = staged,
                stagedNew = map["new"]?.split('\u0000')?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
            )
        }
        return out
    }

    private fun appendCommit(dir: File, commit: Commit) {
        val f = historyFile(dir)
        f.parentFile.mkdirs()
        f.appendText(serialize(commit) + "\n", Charsets.UTF_8)
    }

    /** One commit as a header block. Field and record separators are NUL, which no path may contain. */
    private fun serialize(commit: Commit): String = buildString {
        append("commit ").append(commit.id).append('\n')
        append("parent ").append(commit.parent ?: "").append('\n')
        append("branch ").append(commit.branch).append('\n')
        append("time ").append(commit.time).append('\n')
        append("message ").append(commit.message.replace('\n', ' ')).append('\n')
        append("new ").append(commit.stagedNew.sorted().joinToString("\u0000")).append('\n')
        append("files ")
        commit.staged.forEach { (path, sha) -> append(path).append(':').append(sha).append('\u0000') }
        append('\n')
    }

    private fun head(dir: File): String? = headFile(dir).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    private fun currentBranch(dir: File): String {
        val f = File(gitDir(dir), "pr-branch")
        return f.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null } ?: "main"
    }

    private fun setBranch(dir: File, name: String) {
        val f = File(gitDir(dir), "pr-branch")
        f.parentFile.mkdirs()
        f.writeText("$name\n", Charsets.UTF_8)
    }

    private fun shaOf(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- commands

    private fun init(dir: File): MiniShell.Result {
        val g = gitDir(dir)
        if (g.exists()) return MiniShell.Result(0, "Репозиторий уже создан: $g\n")
        g.mkdirs()
        historyFile(dir).writeText("", Charsets.UTF_8)
        setBranch(dir, "main")
        return MiniShell.Result(0, "Создан пустой репозиторий в $g\n")
    }

    private fun status(dir: File): MiniShell.Result {
        if (!gitDir(dir).isDirectory) {
            return MiniShell.Result(128, "fatal: не git-репозиторий (или родительский каталог)\n")
        }
        val commits = readCommits(dir)
        val staged = readIndex(dir)
        val tracked = commits.lastOrNull()?.staged ?: emptyMap()

        val out = StringBuilder("На ветке ${currentBranch(dir)}\n")
        val newFiles = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        for ((path, sha) in tracked) {
            val f = File(dir, path)
            when {
                !f.exists() -> deleted += path
                shaOf(f) != sha -> changed += path
            }
        }
        collect(dir) { rel, file ->
            if (rel !in tracked && rel !in staged && !isIgnored(rel)) newFiles += rel
        }

        fun section(title: String, list: List<String>) {
            if (list.isEmpty()) return
            out.append("\n$title:\n")
            list.sorted().forEach { out.append("\t").append(it).append('\n') }
        }
        section("Новые файлы", newFiles)
        section("Изменённые", changed)
        section("Удалённые", deleted)
        section("В индексе", staged.keys.toList())
        section("Удалённые в индексе", (tracked.keys - staged.keys).toList())
        if (newFiles.isEmpty() && changed.isEmpty() && deleted.isEmpty() && staged.isEmpty()) {
            out.append("\nРабочая директория чистая\n")
        }
        return MiniShell.Result(0, out.toString())
    }

    private fun add(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        if (!gitDir(dir).isDirectory) return MiniShell.Result(128, "fatal: не git-репозиторий\n")
        val all = args.any { it == "-A" || it == "--all" || it == "." }
        val index = readIndex(dir).toMutableMap()
        var count = 0
        for (a in args) {
            when {
                a == "-A" || a == "--all" || a == "." -> {
                    collect(dir) { rel, _ ->
                        if (!isIgnored(rel)) { index[rel] = "indexed"; count++ }
                    }
                }
                !a.startsWith("-") -> {
                    val f = resolve.resolve(a, dir) ?: return MiniShell.Result(1, "fatal: pathspec '$a' is outside the repository\n")
                    if (f.isDirectory) {
                        val prefix = f.absolutePath
                        collect(dir) { rel, _ ->
                            if (rel.startsWith(f.name) && !isIgnored(rel)) { index[rel] = "indexed"; count++ }
                        }
                    } else {
                        index[f.relativeToOrNull(dir)?.path ?: f.name] = "indexed"
                        count++
                    }
                }
            }
        }
        writeIndex(dir, index)
        return MiniShell.Result(0, "В индекс добавлено: $count\n")
    }

    private fun commit(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        if (!gitDir(dir).isDirectory) return MiniShell.Result(128, "fatal: не git-репозиторий\n")
        val message = args.firstOrNull { !it.startsWith("-") && it != "-m" && it != "-a" }
            ?: args.getOrNull(args.indexOf("-m") + 1)
            ?: return MiniShell.Result(1, "fatal: сообщение коммита не задано (git commit -m 'текст')\n")
        val all = args.any { it == "-a" || it == "-am" }

        val index = readIndex(dir).toMutableMap()
        val commits = readCommits(dir)
        val tracked = commits.lastOrNull()?.staged ?: emptyMap()

        if (all) {
            for ((path, sha) in tracked) {
                val f = File(dir, path)
                if (f.exists()) index[path] = "indexed"
            }
        }

        val staged = HashMap<String, String>()
        val stagedNew = mutableSetOf<String>()
        var changes = 0
        for (path in index.keys) {
            val f = File(dir, path)
            if (!f.isFile) { changes++; continue }
            val sha = shaOf(f)
            if (tracked[path] != sha) {
                storeBlob(dir, f, sha)
                staged[path] = sha
                stagedNew += path
                changes++
            }
        }
        for (path in tracked.keys) {
            if (path !in index && !File(dir, path).exists()) {
                // A deleted file is recorded as staged with an empty digest.
                staged[path] = ""
                stagedNew += path
                changes++
            }
        }
        if (changes == 0) return MiniShell.Result(1, "нечего коммитить, рабочая директория чистая\n")

        val commit = Commit(
            id = UUID.randomUUID().toString().take(7),
            parent = head(dir),
            message = message,
            time = System.currentTimeMillis(),
            branch = currentBranch(dir),
            staged = staged,
            stagedNew = stagedNew,
        )
        appendCommit(dir, commit)
        headFile(dir).writeText(commit.id, Charsets.UTF_8)
        writeIndex(dir, emptyMap())
        return MiniShell.Result(
            0,
            "[${commit.branch} ${commit.id}] $message\n ${changes} файл(ов)\n",
        )
    }

    private fun log(args: List<String>, dir: File): MiniShell.Result {
        val commits = readCommits(dir)
        if (commits.isEmpty()) return MiniShell.Result(0, "")
        val limit = args.indexOf("-n").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: 20
        val out = StringBuilder()
        commits.reversed().take(limit).forEach { c ->
            out.append("commit ${c.id}")
            if (c.parent != null) out.append(" (родитель: ${c.parent})")
            out.append('\n')
                .append("Author: pocketrun <pocketrun@localhost>\n")
                .append("Date:   ${STAMP.format(Instant.ofEpochMilli(c.time))}\n")
                .append("\n    ${c.message}\n\n")
        }
        return MiniShell.Result(0, out.toString())
    }

    private fun show(argsIn: List<String>, dir: File): MiniShell.Result {
        val args = argsIn.filterNot { it == "--stat" || it == "--oneline" }
        val commits = readCommits(dir)
        if (commits.isEmpty()) return MiniShell.Result(128, "fatal: нет коммитов\n")
        val id = args.firstOrNull()?.takeIf { commits.any { c -> c.id.startsWith(it) } }
        val commit = commits.lastOrNull { id == null || it.id.startsWith(id) }
            ?: return MiniShell.Result(128, "fatal: не найден коммит\n")
        val out = StringBuilder(
            "commit ${commit.id}\nAuthor: pocketrun <pocketrun@localhost>\n" +
                "Date:   ${STAMP.format(Instant.ofEpochMilli(commit.time))}\n\n    ${commit.message}\n\n",
        )
        commit.stagedNew.sorted().forEach { path ->
            val digest = commit.staged[path]
            out.append(" ").append(if (digest.isNullOrEmpty()) "D " else "M ").append(path).append('\n')
        }
        return MiniShell.Result(0, out.toString())
    }

    private fun diff(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        val commits = readCommits(dir)
        val tracked = commits.lastOrNull()?.staged ?: emptyMap()
        val targets = args.filter { !it.startsWith("-") }
        val out = StringBuilder()
        var any = false

        fun report(rel: String) {
            val f = File(dir, rel)
            if (!f.isFile) {
                if (tracked[rel] != null) { out.append("-файл $rel\n"); any = true }
                return
            }
            val sha = shaOf(f)
            if (tracked[rel] != sha) {
                val old = snapshot(dir, tracked[rel].orEmpty())
                out.append("--- a/$rel\n+++ b/$rel\n")
                if (old != null) lineDiff(old, f.readText(Charsets.UTF_8)).forEach { out.append(it).append('\n') }
                any = true
            }
        }

        if (targets.isEmpty()) {
            tracked.keys.forEach { report(it) }
            collect(dir) { rel, _ ->
                if (rel !in tracked) { out.append("--- /dev/null\n+++ b/$rel\n(новый файл)\n"); any = true }
            }
        } else {
            for (t in targets) {
                val f = resolve.resolve(t, dir) ?: continue
                if (f.isDirectory) {
                    collect(dir) { rel, _ -> if (!isIgnored(rel)) report(rel) }
                } else report(f.relativeToOrNull(dir)?.path ?: f.name)
            }
        }
        return if (any) MiniShell.Result(1, out.toString()) else MiniShell.Result(0, "")
    }

    private fun lineDiff(old: String, new: String): List<String> {
        val a = old.split("\n")
        val b = new.split("\n")
        val out = mutableListOf<String>()
        var i = 0
        while (i < maxOf(a.size, b.size)) {
            val l = a.getOrNull(i)
            val r = b.getOrNull(i)
            if (l != r) {
                if (l != null) out += "-$l"
                if (r != null) out += "+$r"
            }
            i++
        }
        return out
    }

    private fun branch(args: List<String>, dir: File): MiniShell.Result {
        if (!gitDir(dir).isDirectory) return MiniShell.Result(128, "fatal: не git-репозиторий\n")
        // `git branch <name>` creates and switches; a leading flag takes the next
        // argument as the name, which is how `git branch -m old new` reads.
        val flagIndex = args.indexOfFirst { it.startsWith("-") }
        val create = when {
            flagIndex >= 0 -> args.getOrNull(flagIndex + 1)
            else -> args.firstOrNull()
        }
        if (create != null) {
            setBranch(dir, create)
            return MiniShell.Result(0, "Переключено на новую ветку '$create'\n")
        }
        val seen = LinkedHashSet<String>()
        readCommits(dir).forEach { seen += it.branch }
        seen += currentBranch(dir)
        val out = StringBuilder()
        seen.forEach { name ->
            out.append(if (name == currentBranch(dir)) "* $name" else "  $name").append('\n')
        }
        return MiniShell.Result(0, out.toString())
    }

    private fun checkout(args: List<String>, dir: File): MiniShell.Result {
        val target = args.firstOrNull { !it.startsWith("-") }
            ?: return MiniShell.Result(1, "fatal: нужно имя ветки\n")
        if (target == head(dir)) return MiniShell.Result(0, "Уже на '$target'\n")
        setBranch(dir, target)
        return MiniShell.Result(0, "Переключено на '$target'\n")
    }

    private fun restore(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        val targets = args.filter { !it.startsWith("-") }
        if (targets.isEmpty()) return MiniShell.Result(1, "fatal: нужно имя файла\n")
        val commits = readCommits(dir)
        val target = commits.lastOrNull()
            ?: return MiniShell.Result(128, "fatal: нет коммитов, нечего восстанавливать\n")
        for (t in targets) {
            val f = resolve.resolve(t, dir) ?: return MiniShell.Result(1, "fatal: pathspec '$t' is outside the repository\n")
            val rel = f.relativeToOrNull(dir)?.path ?: f.name
            val content = snapshot(dir, target.staged[rel].orEmpty())
                ?: return MiniShell.Result(1, "fatal: pathspec '$t' не найден в последнем коммите\n")
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
        }
        return MiniShell.Result(0, "Восстановлено: ${targets.size}\n")
    }

    private fun reset(args: List<String>, dir: File): MiniShell.Result {
        val commits = readCommits(dir)
        if (commits.isEmpty()) return MiniShell.Result(128, "fatal: нет коммитов\n")
        val keep = args.filterNot { it.startsWith("-") }.firstOrNull()
        val index = if (keep == null) commits.size - 1 else commits.indexOfLast { it.id.startsWith(keep) }
        if (index < 0) return MiniShell.Result(1, "fatal: ambiguous argument '$keep'\n")
        historyFile(dir).writeText(
            commits.take(index + 1).joinToString("\n\n", postfix = "\n\n") { serialize(it) },
            Charsets.UTF_8,
        )
        headFile(dir).writeText(commits[index].id, Charsets.UTF_8)
        writeIndex(dir, emptyMap())
        return MiniShell.Result(0, "HEAD -> ${commits[index].id}\n")
    }

    private fun revParse(args: List<String>, dir: File): MiniShell.Result {
        val what = args.firstOrNull() ?: "HEAD"
        return when (what) {
            "HEAD" -> MiniShell.Result(0, head(dir) ?: "неизвестно\n")
            "--abbrev-ref", "--abbrev-ref=HEAD" -> MiniShell.Result(0, "${currentBranch(dir)}\n")
            "--is-inside-work-tree" -> MiniShell.Result(0, if (gitDir(dir).isDirectory) "true\n" else "false\n")
            else -> MiniShell.Result(1, "fatal: bad revision '$what'\n")
        }
    }

    // ---------------------------------------------------------------- index / snapshots

    private fun readIndex(dir: File): Map<String, String> {
        val f = indexFile(dir)
        if (!f.isFile) return emptyMap()
        return f.readText(Charsets.UTF_8).split('\n').filter { it.isNotBlank() }.associate {
            it.substringBefore('\u0000') to it.substringAfter('\u0000', "")
        }
    }

    private fun writeIndex(dir: File, index: Map<String, String>) {
        val f = indexFile(dir)
        f.parentFile.mkdirs()
        f.writeText(index.keys.joinToString("\n") { "$it\u0000" } + if (index.isEmpty()) "" else "\n", Charsets.UTF_8)
    }

    /**
     * The content of a file as of the commit that recorded [sha].
     *
     * Every committed version is copied into .git/pr-objects under its digest when
     * the commit is written, so an older version is always recoverable: a digest
     * on its own only says *which* content it was, not what it was.
     */
    private fun snapshot(dir: File, sha: String): String? {
        if (sha.isEmpty()) return null
        val blob = File(File(gitDir(dir), "pr-objects"), sha)
        return blob.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }

    /** Copies [file]'s content into the object store under its digest. */
    private fun storeBlob(dir: File, file: File, sha: String) {
        val objects = File(gitDir(dir), "pr-objects")
        objects.mkdirs()
        val blob = File(objects, sha)
        if (!blob.exists()) runCatching { blob.writeText(file.readText(Charsets.UTF_8), Charsets.UTF_8) }
    }

    private fun isIgnored(rel: String): Boolean {
        if (rel.startsWith(".git/")) return true
        val top = rel.substringBefore('/')
        return top in setOf("node_modules", "build", "__pycache__", ".gradle", ".venv", "venv", "dist")
    }

    /** Every file under [dir], skipping ignored trees, as paths relative to it. */
    private inline fun collect(dir: File, block: (String, File) -> Unit) {
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        var count = 0
        while (stack.isNotEmpty() && count < 20_000) {
            val current = stack.removeLast()
            for (child in current.listFiles().orEmpty()) {
                count++
                val rel = child.relativeToOrNull(dir)?.path ?: continue
                if (isIgnored(rel)) continue
                if (child.isDirectory) stack.addLast(child) else block(rel, child)
            }
        }
    }
}
