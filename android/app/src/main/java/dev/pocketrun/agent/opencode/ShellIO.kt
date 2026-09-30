package dev.pocketrun.agent.opencode

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Network and archive tools for the sandbox shell.
 *
 * `curl` is the one that matters: an agent that cannot fetch a URL has to write
 * a Node script to do it, and `fetch` through the JS bridge is synchronous and
 * capped at 4 MB. This goes through the same JVM HTTP stack, so it inherits the
 * same caps, and it writes to disk instead of returning a wall of text.
 *
 * Archives use java.util.zip, which is already on the device, plus the same
 * unsafe-path refusal as every other writer: a tar or zip that tries to write
 * outside the workspace is rejected before anything lands on disk.
 */
internal object ShellIO {

    private const val TAG = "ShellIO"
    private const val MAX_DOWNLOAD = 64L * 1024 * 1024
    private const val MAX_BODY = 4L * 1024 * 1024
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 60_000


    fun run(name: String, args: List<String>, dir: File, stdin: String, resolve: ShellText.Resolve): MiniShell.Result? =
        when (name) {
            "curl" -> curl(args, dir, resolve)
            "wget" -> curl(listOf("-O") + args, dir, resolve)
            "gzip" -> gzip(args, dir, stdin, resolve, compress = true)
            "gunzip" -> gzip(args, dir, stdin, resolve, compress = false)
            "zip" -> zip(args, dir, resolve, compress = true)
            "unzip" -> unzip(args, dir, resolve)
            else -> null
        }

    // ---------------------------------------------------------------- curl

    private fun curl(argsIn: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        var url: String? = null
        var output: String? = null
        var method = "GET"
        var data: String? = null
        var silent = false
        var showHeaders = false
        var head = false
        val headers = mutableListOf<Pair<String, String>>()
        fun addHeader(h: String) {
            h.split(':', limit = 2).takeIf { it.size == 2 }?.let { headers += it[0].trim() to it[1].trim() }
        }
        var i = 0
        var noMoreFlags = false
        while (i < argsIn.size) {
            val a = argsIn[i]
            fun inlineFlag(prefix: String): String? =
                if (a == prefix) argsIn.getOrNull(i + 1) else a.removePrefix(prefix).takeIf { it.isNotEmpty() }

            when {
                noMoreFlags || !a.startsWith("-") || a == "-" -> url = a
                a == "--" -> noMoreFlags = true
                a.startsWith("--output") -> { output = inlineFlag("--output"); i++ }
                a.startsWith("-o") -> { output = inlineFlag("-o"); i++ }
                a.startsWith("--header") -> { inlineFlag("--header")?.let(::addHeader); i++ }
                a.startsWith("-H") -> { inlineFlag("-H")?.let(::addHeader); i++ }
                a.startsWith("--request") -> { method = inlineFlag("--request")?.uppercase() ?: "GET"; i++ }
                a.startsWith("-X") -> { method = inlineFlag("-X")?.uppercase() ?: "GET"; i++ }
                a.startsWith("--data") -> { data = inlineFlag("--data"); i++ }
                a.startsWith("-d") -> { data = inlineFlag("-d"); i++ }
                a in setOf("-s", "--silent", "-S", "--show-error", "-sS") -> silent = true
                a in setOf("-i", "--include") -> showHeaders = true
                a in setOf("-I", "--head") -> head = true
                // -L, -k, --fail and friends keep curl's own defaults.
                else -> Unit
            }
            i++
        }

        val target = url
            ?: return MiniShell.Result(2, "curl: нужен URL\n")
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return MiniShell.Result(3, "curl: только http и https (получено «$target»)\n")
        }

        return try {
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.requestMethod = if (head) "HEAD" else method
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (!data.isNullOrEmpty()) {
                val bytes = data.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            // To stdout a page of JSON is fine; to a file the cap is generous.
            val cap = if (output != null) MAX_DOWNLOAD else MAX_BODY
            val payload = readCapped(conn, status, cap)

            val report = StringBuilder()
            if (showHeaders) {
                report.append("HTTP/1.1 $status ${conn.responseMessage ?: ""}\n")
                for ((k, v) in conn.headerFields) {
                    if (k != null && v.isNotEmpty()) report.append("$k: ${v.joinToString(", ")}\n")
                }
                report.append('\n')
            }

            if (status !in 200..399) {
                if (!silent) report.append("curl: (${status}) $target\n")
                return MiniShell.Result(22, report.toString())
            }

            if (output != null) {
                val file = resolve.resolve(output, dir)
                    ?: return MiniShell.Result(3, "curl: путь вне песочницы\n")
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { it.write(payload) }
                if (!silent) report.append("Сохранено в ${file.name} (${payload.size / 1024} КБ)\n")
            } else if (!silent && !head) {
                report.append(String(payload, Charsets.UTF_8))
                if (payload.isNotEmpty() && payload.last() != '\n'.code.toByte()) report.append('\n')
            }
            MiniShell.Result(0, report.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "curl $target failed", t)
            MiniShell.Result(7, if (silent) "" else "curl: (7) не удалось соединиться: ${t.message}\n")
        }
    }

    private fun readCapped(conn: HttpURLConnection, status: Int, cap: Long): ByteArray {
        val stream = if (status in 200..399) conn.inputStream else conn.errorStream ?: return ByteArray(0)
        return stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (total < cap) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toByteArray()
        }
    }

    // ---------------------------------------------------------------- gzip

    private fun gzip(args: List<String>, dir: File, stdin: String, resolve: ShellText.Resolve, compress: Boolean): MiniShell.Result {
        val files = args.filter { !it.startsWith("-") }
        if (files.isEmpty()) {
            // filter mode: stdin to stdout
            val out = java.io.ByteArrayOutputStream()
            if (compress) {
                GZIPOutputStream(out).use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
            } else {
                GZIPInputStream(stdin.toByteArray(Charsets.UTF_8).inputStream()).use { it.copyTo(out) }
            }
            return MiniShell.Result(0, String(out.toByteArray(), Charsets.ISO_8859_1))
        }
        var exit = 0
        for (f in files) {
            val file = resolve.resolve(f, dir) ?: continue
            val target = File(file.parentFile, file.name + (if (compress) ".gz" else ""))
            try {
                if (compress) {
                    GZIPOutputStream(FileOutputStream(target)).use { it.write(file.readBytes()) }
                    file.delete()
                } else {
                    val out = java.io.ByteArrayOutputStream()
                    GZIPInputStream(file.inputStream()).use { it.copyTo(out) }
                    file.writeBytes(out.toByteArray())
                    target.delete()
                }
            } catch (t: Throwable) {
                exit = 1
            }
        }
        return MiniShell.Result(exit, "")
    }

    // ---------------------------------------------------------------- zip

    private fun zip(args: List<String>, dir: File, resolve: ShellText.Resolve, compress: Boolean): MiniShell.Result {
        val recursive = args.any { it == "-r" }
        val archive = args.firstOrNull { it.endsWith(".zip") }
            ?: return MiniShell.Result(2, "zip: нужно имя архива .zip\n")
        val files = args.filter { !it.startsWith("-") && it != archive }
        if (files.isEmpty()) return MiniShell.Result(2, "zip: нет файлов\n")
        val target = resolve.resolve(archive, dir) ?: return MiniShell.Result(3, "zip: путь вне песочницы\n")

        val collected = mutableListOf<File>()
        for (f in files) {
            val file = resolve.resolve(f, dir) ?: continue
            if (file.isDirectory && recursive) collect(file, collected) else collected += file
        }
        return try {
            ZipOutputStream(FileOutputStream(target)).use { zos ->
                val base = target.parentFile
                for (file in collected) {
                    val entry = ZipEntry(file.relativeToOrNull(base)?.path ?: file.name)
                    zos.putNextEntry(entry)
                    if (file.isFile) zos.write(file.readBytes())
                    zos.closeEntry()
                }
            }
            MiniShell.Result(0, "")
        } catch (t: Throwable) {
            MiniShell.Result(1, "zip: ${t.message}\n")
        }
    }

    private fun unzip(args: List<String>, dir: File, resolve: ShellText.Resolve): MiniShell.Result {
        val list = args.any { it == "-l" }
        val destArg = args.indexOf("-d").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val archive = args.firstOrNull { it.endsWith(".zip") && it != destArg }
            ?: return MiniShell.Result(2, "unzip: нужен архив .zip\n")
        val file = resolve.resolve(archive, dir)?.takeIf { it.isFile }
            ?: return MiniShell.Result(2, "unzip: $archive: нет такого файла\n")
        val dest = (destArg?.let { resolve.resolve(it, dir) } ?: resolve.resolve(".", dir))
            ?: return MiniShell.Result(3, "unzip: путь вне песочницы\n")
        dest.mkdirs()

        return try {
            ZipInputStream(file.inputStream().buffered()).use { zis ->
                var count = 0
                var exit = 0
                var listing = StringBuilder()
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val out = safeJoin(dest, entry.name)
                    if (out == null) {
                        // A zip that tries to escape the sandbox is refused outright.
                        return MiniShell.Result(2, "unzip: запись «${entry.name}» указывает вне рабочей папки\n")
                    }
                    if (list) {
                        listing.append(entry.name).append('\n')
                    } else {
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        count++
                    }
                    zis.closeEntry()
                }
                if (list) MiniShell.Result(0, listing.toString()) else MiniShell.Result(exit, "$count файлов\n")
            }
        } catch (t: Throwable) {
            MiniShell.Result(1, "unzip: ${t.message}\n")
        }
    }

    /** Joins a relative entry name onto [dir], refusing anything that escapes it. */
    private fun safeJoin(dir: File, name: String): File? {
        if (name.startsWith("/") || name.contains("..")) return null
        val out = File(dir, name).canonicalFile
        val base = dir.canonicalFile
        return if (out.path == base.path || out.path.startsWith(base.path + File.separator)) out else null
    }

    private fun collect(dir: File, into: MutableList<File>, depth: Int = 0) {
        if (depth > 12) return
        dir.listFiles()?.forEach { child ->
            into += child
            if (child.isDirectory) collect(child, into, depth + 1)
        }
    }
}
