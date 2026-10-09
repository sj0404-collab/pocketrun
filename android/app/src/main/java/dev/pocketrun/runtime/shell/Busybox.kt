package dev.pocketrun.runtime.shell

import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.ExecResult
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * BusyBox 1.34.1 (meefik's Android build) packaged as `libbusybox.so` so
 * Android extracts it to `nativeLibraryDir`, where it can be exec'd. It gives
 * the mini-shell the long tail of real Unix utilities (sed, awk, sort, uniq,
 * cut, tr, tar, gzip, xargs, wget, diff, ...).
 *
 * Applets are real native processes. They run with their cwd inside the
 * workspace, but — unlike the sandboxed built-ins — they can touch anything the
 * app's UID can, so the workspace jail does not apply to them.
 */
class Busybox(
    nativeLibraryDir: File,
    private val workspace: Workspace,
    private val defaultTimeoutMs: Long = 120_000L,
) {

    companion object {
        private const val MAX_CAPTURE = 256 * 1024
        const val VERSION = "BusyBox 1.34.1"
    }

    private val binary = File(nativeLibraryDir, "libbusybox.so")

    /** Absolute path of the executable, for `which` output. */
    val binaryPath: String get() = binary.absolutePath

    @Volatile
    private var cachedApplets: Set<String>? = null

    fun isAvailable(): Boolean = binary.isFile

    /** Applet names from `busybox --list`; empty when the binary is missing. */
    fun applets(): Set<String> {
        cachedApplets?.let { return it }
        if (!isAvailable()) return emptySet()
        val listed = run(listOf("--list"), workspace.root, null, 15_000L)
        val set = listed.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        cachedApplets = set
        return set
    }

    fun knowsApplet(name: String): Boolean = name in applets()

    /**
     * Runs `busybox <args...>` (args[0] is the applet). Returns 124 on timeout,
     * 127 when the binary is missing.
     */
    fun run(
        args: List<String>,
        cwd: File,
        stdin: String?,
        timeoutMs: Long = defaultTimeoutMs,
    ): ExecResult {
        if (!isAvailable()) return ExecResult(127, "", "busybox недоступен\n", 0)
        if (args.isEmpty()) return ExecResult(1, "", "busybox: нужен applet\n", 0)

        val startedAt = System.currentTimeMillis()
        val command = ArrayList<String>(args.size + 1)
        command.add(binary.absolutePath)
        command.addAll(args)

        val pb = ProcessBuilder(command)
        pb.directory(cwd.takeIf { it.isDirectory } ?: workspace.root)
        pb.environment()["HOME"] = workspace.root.absolutePath
        pb.environment()["LANG"] = "C.UTF-8"

        val proc = pb.start()
        val out = StringBuilder()
        val err = StringBuilder()

        val outReader = Thread {
            runCatching {
                proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { appendCapped(out, it + "\n") }
                }
            }
        }
        val errReader = Thread {
            runCatching {
                proc.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { appendCapped(err, it + "\n") }
                }
            }
        }
        outReader.start()
        errReader.start()
        try {
            proc.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                if (!stdin.isNullOrEmpty()) writer.write(stdin)
            }
        } catch (_: Exception) {
        }

        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            proc.waitFor(2, TimeUnit.SECONDS)
            outReader.join(1_000)
            errReader.join(1_000)
            return ExecResult(
                124,
                out.toString(),
                (err.toString() + "превышено время выполнения busybox (${timeoutMs / 1000} с)\n"),
                System.currentTimeMillis() - startedAt,
                truncated = out.length >= MAX_CAPTURE || err.length >= MAX_CAPTURE,
            )
        }
        outReader.join(2_000)
        errReader.join(2_000)
        return ExecResult(
            exitCode = proc.exitValue(),
            stdout = out.toString(),
            stderr = err.toString(),
            durationMs = System.currentTimeMillis() - startedAt,
            truncated = out.length >= MAX_CAPTURE || err.length >= MAX_CAPTURE,
        )
    }

    private fun appendCapped(sb: StringBuilder, text: String) {
        if (sb.length >= MAX_CAPTURE) return
        sb.append(text, 0, minOf(text.length, MAX_CAPTURE - sb.length))
    }
}
