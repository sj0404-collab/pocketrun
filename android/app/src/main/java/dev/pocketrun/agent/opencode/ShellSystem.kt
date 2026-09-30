package dev.pocketrun.agent.opencode

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Filesystem and environment introspection for the sandbox shell.
 *
 * These exist because an agent working in an unfamiliar folder asks the same
 * three questions constantly - how big is this, what is it, how much room is
 * left - and previously had no way to answer any of them except by writing a
 * throwaway script.
 */
internal object ShellSystem {


    fun run(name: String, args: List<String>, dir: File, stdin: String, resolve: ShellText.Resolve): MiniShell.Result? =
        when (name) {
            "stat" -> stat(args, dir, resolve)
            "du" -> du(args, dir, resolve)
            "df" -> df(args, dir)
            "file" -> file(args, dir, resolve)
            "ln" -> ln(args, dir, resolve)
            "chmod" -> chmod(args, dir, resolve)
            "realpath" -> realpath(args, dir, resolve)
            "readlink" -> readlink(args, dir, resolve)
            "mktemp" -> mktemp(args, dir, resolve)
            "base64" -> base64(args, dir, stdin, resolve)
            "sha256sum", "shasum" -> digest(args, dir, stdin, resolve, "SHA-256")
            "md5sum" -> digest(args, dir, stdin, resolve, "MD5")
            "xxd", "hexdump" -> xxd(args, dir, stdin, resolve)
            "tree" -> tree(args, dir, resolve)
            "id", "whoami" -> MiniShell.Result(0, "pocketrun\n")
            "hostname" -> MiniShell.Result(0, "pocketrun\n")
            "ps" -> MiniShell.Result(0, "    PID COMMAND\\n      1 pocketrun\\n")
            "test", "[" -> test(args, dir, resolve)
            "kill" -> MiniShell.Result(1, "kill: в песочнице нет других процессов\\n")
            "history" -> MiniShell.Result(0, "")
            else -> null
        }

    // ---------------------------------------------------------------- stat

    private fun stat(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        val targets = args.filter { !it.startsWith("-") }
        if (targets.isEmpty()) return MiniShell.Result(1, "stat: нужен файл\n")
        val format = args.firstOrNull { it.startsWith("--format=") }?.removePrefix("--format=")
        val out = StringBuilder()
        var exit = 0
        for (t in targets) {
            val f = resolve.resolve(t, dir)
            if (f == null || !f.exists()) {
                exit = 1
                out.append("stat: $t: нет такого файла\n")
                continue
            }
            val size = if (f.isDirectory) 4096 else f.length()
            val perms = if (f.canWrite()) "rw-r--r--" else "r--r--r--"
            val kind = if (f.isDirectory) "directory" else "regular file"
            out.append(
                when (format) {
                    "%n" -> "$t\n"
                    "%s" -> "$size\n"
                    "%F" -> "$kind\n"
                    "%a" -> "${"755".takeIf { f.canWrite() } ?: "555"}\n"
                    "%U", "%u" -> "pocketrun/pocketrun\n"
                    "%Y", "%y" -> "${f.lastModified() / 1000}\n"
                    "%N" -> "$t\n"
                    else -> "  File: $t\n  Size: $size\t  $kind\nAccess: ($perms)\nModify: ${f.lastModified()}\n"
                },
            )
        }
        return MiniShell.Result(exit, out.toString())
    }

    private fun du(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        val summarise = args.any { it == "-s" || it == "-sh" }
        val human = args.any { it == "-h" || it == "-sh" }
        val all = args.any { it == "-a" }
        val targets = args.filter { !it.startsWith("-") }.ifEmpty { listOf(".") }
        val out = StringBuilder()
        for (t in targets) {
            val f = resolve.resolve(t, dir)
            if (f == null || !f.exists()) {
                out.append("du: $t: нет такого файла\n")
                continue
            }
            if (summarise || !f.isDirectory) {
                out.append(fmtSize(dirSize(f), human) + "\t" + (f.relativeToOrNull(dir)?.path ?: f.name) + "\n")
            } else {
                out.append(fmtSize(dirSize(f), human) + "\t.\n")
                if (all || !summarise) {
                    f.listFiles()?.sortedBy { it.name }?.forEach { child ->
                        out.append(
                            fmtSize(if (child.isDirectory) dirSize(child) else child.length(), human) +
                                "\t" + (child.relativeToOrNull(dir)?.path ?: child.name) + "\n",
                        )
                    }
                }
            }
        }
        return MiniShell.Result(0, out.toString())
    }

    private fun df(args: List<String>, dir: File): MiniShell.Result {
        val root = dir.absolutePath
        val runtime = File("/").usableSpace
        val total = File("/").totalSpace
        val used = total - runtime
        return MiniShell.Result(
            0,
            "Файловая система        1K-блоки  Использовано  Доступно  Использование  Куда\n" +
                "pocketrun               ${(total / 1024).toString().padStart(9)} " +
                "${(used / 1024).toString().padStart(10)} ${(runtime / 1024).toString().padStart(9)} " +
                "${if (total > 0) used * 100 / total else 0}%  $root\n",
        )
    }

    private fun file(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        val targets = args.filter { !it.startsWith("-") }.ifEmpty { return MiniShell.Result(1, "file: нужен файл\n") }
        val out = StringBuilder()
        var exit = 0
        for (t in targets) {
            val f = resolve.resolve(t, dir)
            if (f == null || !f.exists()) { exit = 1; out.append("$t: нет такого файла\n"); continue }
            out.append("$t: ${describe(f)}\n")
        }
        return MiniShell.Result(exit, out.toString())
    }

    private fun describe(f: File): String {
        if (f.isDirectory) return "directory"
        val name = f.name.lowercase(Locale.ROOT)
        val ext = f.extension
        val known = when (ext) {
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic" -> "image data"
            "mp3", "wav", "ogg", "m4a", "flac" -> "audio data"
            "mp4", "mkv", "webm", "mov", "avi" -> "video data"
            "gz", "tgz", "zip", "7z", "rar" -> "archive data"
            "js", "ts", "json", "py", "kt", "java", "c", "cpp", "h", "go", "rs" -> "source text"
            "txt", "md", "csv", "log", "yml", "yaml", "toml", "html", "css" -> "text"
            else -> null
        }
        if (known != null) return "$known"
        // A NUL byte in the head is the same signal the editor uses.
        val bytes = f.inputStream().use { input ->
            val buffer = ByteArray(minOf(4096L, f.length()).toInt().coerceAtLeast(1))
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            if (read == buffer.size) buffer else buffer.copyOf(read)
        }
        if (bytes != null && bytes.any { it == 0.toByte() }) return "data"
        return if (f.length() == 0L) "empty" else "ASCII text"
    }

    // ---------------------------------------------------------------- links / perms

    private fun ln(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        val symbolic = args.any { it == "-s" }
        val force = args.any { it == "-f" }
        val targets = args.filter { !it.startsWith("-") }
        if (targets.size != 2) return MiniShell.Result(1, "ln: нужны ссылка и имя\n")
        val from = resolve.resolve(targets[0], dir) ?: return MiniShell.Result(1, "ln: путь вне песочницы\n")
        val to = resolve.resolve(targets[1], dir) ?: return MiniShell.Result(1, "ln: путь вне песочницы\n")
        if (to.exists() && !force) return MiniShell.Result(1, "ln: $targets[1]: уже существует\n")
        return try {
            if (symbolic) {
                // Android's java.io has no symlink; a recorded link is honest and
                // readable by the app's own resolver.
                to.writeText("pr-symlink:${from.absolutePath}", Charsets.UTF_8)
            } else {
                from.copyTo(to, overwrite = force)
            }
            MiniShell.Result(0, "")
        } catch (t: Throwable) {
            MiniShell.Result(1, "ln: ${t.message}\n")
        }
    }

    private fun chmod(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        val specs = args.filter { !it.startsWith("-") }
        if (specs.size < 2) return MiniShell.Result(1, "chmod: нужно 'chmod 755 файл'\n")
        val mode = specs[0]
        var exit = 0
        for (t in specs.drop(1)) {
            val f = resolve.resolve(t, dir)
            if (f == null || !f.exists()) { exit = 1; continue }
            // Only the writable bit is meaningful inside the sandbox, so it is the
            // only one that is stored - and the real filesystem is still the truth.
            if (!mode.startsWith("-")) {
                runCatching {
                    val writable = mode.any { it in "2367" }
                    f.setWritable(writable, false)
                    f.setWritable(writable)
                }
            }
        }
        return MiniShell.Result(exit, "")
    }

    private fun realpath(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        val targets = args.filter { !it.startsWith("-") }.ifEmpty { return MiniShell.Result(1, "realpath: нужен путь\n") }
        val out = StringBuilder()
        var exit = 0
        for (t in targets) {
            val f = resolve.resolve(t, dir)
            if (f == null) { exit = 1; out.append("realpath: $t: нет такого файла\n"); continue }
            out.append(f.absolutePath + "\n")
        }
        return MiniShell.Result(exit, out.toString())
    }

    private fun readlink(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        val t = args.firstOrNull { !it.startsWith("-") } ?: return MiniShell.Result(1, "readlink: нужен путь\n")
        val f = resolve.resolve(t, dir) ?: return MiniShell.Result(1, "readlink: нет такого файла\n")
        val text = if (f.isFile) runCatching { f.readText() }.getOrNull() else null
        return if (text?.startsWith("pr-symlink:") == true) {
            MiniShell.Result(0, text.removePrefix("pr-symlink:") + "\n")
        } else {
            MiniShell.Result(1, "")
        }
    }

    private fun mktemp(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        val template = args.firstOrNull { !it.startsWith("-") } ?: "tmp.XXXXXX"
        val parent = if (template.contains('/')) template.substringBeforeLast('/') else "."
        val dirFile = resolve.resolve(parent, dir)?.takeIf { it.isDirectory }
            ?: return MiniShell.Result(1, "mktemp: путь вне песочницы\n")
        val prefix = template.substringAfterLast('/').takeWhile { it != 'X' }.ifEmpty { "tmp." }
        repeat(20) {
            val f = File(dirFile, prefix + java.util.UUID.randomUUID().toString().take(6))
            if (f.createNewFile()) {
                return MiniShell.Result(0, (f.relativeToOrNull(dir)?.path ?: f.name) + "\n")
            }
        }
        return MiniShell.Result(1, "mktemp: не удалось создать файл\n")
    }

    // ---------------------------------------------------------------- encoding

    private fun base64(args: List<String>, dir: File, stdin: String, resolve: ShellText.Resolve): MiniShell.Result {
        val decode = args.any { it == "-d" || it == "--decode" }
        val files = args.filterNot { it.startsWith("-") }
        val text = readAll(files, dir, stdin, resolve)
            ?: return MiniShell.Result(1, "base64: нет такого файла\n")
        return try {
            if (decode) {
                val raw = java.util.Base64.getMimeDecoder().decode(text.filterNot { it.isWhitespace() })
                MiniShell.Result(0, String(raw, Charsets.UTF_8))
            } else {
                val encoded = java.util.Base64.getMimeEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
                MiniShell.Result(0, encoded.chunked(76).joinToString("\n") + "\n")
            }
        } catch (t: Throwable) {
            MiniShell.Result(1, "base64: ${t.message}\n")
        }
    }

    private fun digest(args: List<String>, dir: File, stdin: String, resolve: ShellText.Resolve, algo: String): MiniShell.Result {
        val files = args.filter { !it.startsWith("-") }
        val out = StringBuilder()
        if (files.isEmpty()) {
            val bytes = stdin.toByteArray(Charsets.UTF_8)
            out.append(hex(MessageDigest.getInstance(algo).digest(bytes))).append("  -\n")
            return MiniShell.Result(0, out.toString())
        }
        var exit = 0
        for (f in files) {
            val file = resolve.resolve(f, dir)
            if (file == null || !file.isFile) { exit = 1; out.append("$f: нет такого файла\n"); continue }
            if (file.length() > 64L * 1024 * 1024) { exit = 1; out.append("$f: файл слишком большой\n"); continue }
            out.append(hex(MessageDigest.getInstance(algo).digest(file.readBytes())))
                .append("  ").append(file.name).append('\n')
        }
        return MiniShell.Result(exit, out.toString())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun xxd(args: List<String>, dir: File, stdin: String, resolve: ShellText.Resolve): MiniShell.Result {
        val files = args.filter { !it.startsWith("-") }
        val bytes = readBytes(files, dir, stdin, resolve) ?: return MiniShell.Result(1, "xxd: нет данных\n")
        val out = StringBuilder()
        var offset = 0
        while (offset < bytes.size && offset < 4096) {
            val chunk = bytes.copyOfRange(offset, minOf(offset + 16, bytes.size))
            val hexPart = chunk.joinToString(" ") { "%02x".format(it) }
            val ascii = chunk.map { if (it.toInt() in 32..126) it.toInt().toChar() else '.' }.joinToString("")
            out.append("%08x: %-47s %s\n".format(offset, hexPart, ascii))
            offset += 16
        }
        return MiniShell.Result(0, out.toString())
    }

    // ---------------------------------------------------------------- tree / test

    private fun tree(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        val maxDepth = args.firstOrNull { it.startsWith("-L") }?.removePrefix("-L")?.toIntOrNull() ?: 3
        val dirsOnly = args.any { it == "-d" }
        val root = args.firstOrNull { !it.startsWith("-") }?.let { resolve.resolve(it, dir) } ?: dir
        if (root == null || !root.exists()) return MiniShell.Result(1, "tree: нет такого каталога\n")
        val out = StringBuilder()
        out.append(root.name.ifEmpty { root.path }).append('\n')
        var dirs = 0
        var files = 0
        fun walk(f: File, prefix: String, depth: Int) {
            if (depth > maxDepth) return
            val entries = f.listFiles()?.sortedBy { it.name } ?: return
            entries.forEachIndexed { i, child ->
                val last = i == entries.lastIndex
                val connector = if (last) "└── " else "├── "
                val isDir = child.isDirectory
                if (isDir) dirs++ else if (!dirsOnly) files++
                out.append(prefix).append(connector).append(child.name).append('\n')
                if (isDir) walk(child, prefix + if (last) "    " else "│   ", depth + 1)
            }
        }
        walk(root, "", 1)
        out.append("\n$dirs directories, $files files\n")
        return MiniShell.Result(0, out.toString())
    }

    /** POSIX test/[ for the file and string predicates, enough to script safely. */
    private fun test(argsIn: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        var args = argsIn
        if (args.firstOrNull() == "[" && args.lastOrNull() == "]") args = args.drop(1).dropLast(1)
        fun ok(v: Boolean) = MiniShell.Result(if (v) 0 else 1, "")
        if (args.size == 1) return ok(args[0].isNotEmpty())
        if (args.size == 2) {
            val op = args[0]
            val v = args[1]
            val f = resolve.resolve(v, dir)
            return ok(
                when (op) {
                    "-f" -> f?.isFile == true
                    "-d" -> f?.isDirectory == true
                    "-e" -> f?.exists() == true
                    "-s" -> (f?.length() ?: 0L) > 0
                    "-r", "-w" -> f?.canRead() == true
                    "-L", "-h" -> f?.isFile == true
                    "-z" -> v.isEmpty()
                    "-n" -> v.isNotEmpty()
                    else -> false
                },
            )
        }
        if (args.size == 3) {
            val (a, op, b) = args
            return ok(
                when (op) {
                    "=" , "==" -> a == b
                    "!=" -> a != b
                    "-eq" -> (a.toLongOrNull() ?: 0L) == (b.toLongOrNull() ?: 0L)
                    "-ne" -> (a.toLongOrNull() ?: 0L) != (b.toLongOrNull() ?: 0L)
                    "-lt" -> (a.toLongOrNull() ?: 0L) < (b.toLongOrNull() ?: 0L)
                    "-le" -> (a.toLongOrNull() ?: 0L) <= (b.toLongOrNull() ?: 0L)
                    "-gt" -> (a.toLongOrNull() ?: 0L) > (b.toLongOrNull() ?: 0L)
                    "-ge" -> (a.toLongOrNull() ?: 0L) >= (b.toLongOrNull() ?: 0L)
                    else -> false
                },
            )
        }
        return MiniShell.Result(2, "test: недостаточно аргументов\n")
    }

    // ---------------------------------------------------------------- helpers

    private fun readAll(files: List<String>, dir: File, stdin: String, resolve: ShellText.Resolve): String? {
        if (files.isEmpty()) return stdin
        val out = StringBuilder()
        for (f in files) {
            val file = resolve.resolve(f, dir) ?: return null
            if (!file.isFile) return null
            out.append(file.readText(Charsets.UTF_8))
        }
        return out.toString()
    }

    private fun readBytes(files: List<String>, dir: File, stdin: String, resolve: ShellText.Resolve): ByteArray? {
        if (files.isEmpty()) return stdin.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        for (f in files) {
            val file = resolve.resolve(f, dir) ?: return null
            if (!file.isFile) return null
            out.write(file.readBytes())
        }
        return out.toByteArray()
    }

    private fun dirSize(dir: File): Long {
        if (dir.isFile) return dir.length()
        var total = 0L
        var count = 0
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty() && count < File_TREE_LIMIT) {
            for (child in stack.removeLast().listFiles().orEmpty()) {
                count++
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }

    private const val File_TREE_LIMIT = 20_000

    private fun fmtSize(bytes: Long, human: Boolean): String =
        if (!human) bytes.toString() else dev.pocketrun.core.FileText.human(bytes)
}
