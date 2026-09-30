package dev.pocketrun.runtime.js

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.ExecResult
import dev.pocketrun.runtime.ExecutionHandle
import dev.pocketrun.runtime.OutputListener
import dev.pocketrun.runtime.OutputTailer
import dev.pocketrun.runtime.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The JavaScript runtime: QuickJS evaluating [bootSource] (node/boot.js from the
 * APK assets) plus the event loop the boot script drives through its `__pr*`
 * hooks.
 *
 * This used to be Rhino 1.8.1, which is what forced boot.js to be written in ES5
 * and quietly broke every modern npm package: Rhino cannot parse `class`,
 * `async`/`await`, generators, optional chaining or ES modules, so a package would
 * install successfully and then fail to load with a syntax error the user could do
 * nothing about. QuickJS understands all of it, which is the entire reason for the
 * swap.
 *
 * The bridge contract still lives at the top of boot.js; this class is its only
 * implementation. Scripts reach the outside world only through the bridge, and
 * every filesystem call is contained inside the workspace root by
 * [Workspace.resolve].
 *
 * Runs are serialized on one worker thread; output is streamed through per-run log
 * files exactly like PythonRuntime.
 */
class JsRuntime(
    private val bootSource: String,
    private val workspace: Workspace,
    private val maxRunMs: Long = 10 * 60_000L,
) {

    companion object {
        private const val TAG = "JsRuntime"
        private const val MAX_CAPTURE = 256 * 1024
        private const val TAILER_JOIN_MS = 2_000L
        private const val MAX_READ = 16 * 1024 * 1024
        private const val MAX_HTTP_BODY = 4 * 1024 * 1024

        /** A runaway script gets stopped by the engine itself, not just by the loop. */
        private const val MAX_HEAP_BYTES = 512L * 1024 * 1024

        /** Deep recursion in a script must produce a stack error, never a native crash. */
        private const val MAX_STACK_BYTES = 4L * 1024 * 1024

        const val VERSION = "QuickJS (ES2023)"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pocketrun-js-run").apply { isDaemon = true }
    }

    /** Per-run mutable state, confined to the worker thread while a run is live. */
    private class RunState(
        val request: ExecRequest,
        val outFile: File,
        val errFile: File,
        defaultRoot: File,
    ) {
        var cwd: File = request.cwd?.takeIf { it.isDirectory } ?: defaultRoot
        val stdout = Capture()
        val stderr = Capture()

        val outWriter by lazy { OutputStreamWriter(FileOutputStream(outFile, true), Charsets.UTF_8) }
        val errWriter by lazy { OutputStreamWriter(FileOutputStream(errFile, true), Charsets.UTF_8) }
    }

    fun execute(request: ExecRequest, listener: OutputListener): ExecutionHandle {
        val cancelled = AtomicBoolean(false)
        val running = AtomicBoolean(true)
        val startedAt = System.currentTimeMillis()
        val runDir = File(workspace.cache, "runs/${UUID.randomUUID()}").apply { mkdirs() }
        val state = RunState(request, File(runDir, "out.log"), File(runDir, "err.log"), workspace.root)
        val stopped = AtomicBoolean(false)

        val outTailer = OutputTailer(state.outFile, OutputStream.STDOUT, CaptureListener(listener, state.stdout), stopped) {}
        val errTailer = OutputTailer(state.errFile, OutputStream.STDERR, CaptureListener(listener, state.stderr), stopped) {}
        outTailer.start()
        errTailer.start()

        val future: Future<Int> = executor.submit(Callable {
            try {
                val exitCode = runLocked(state, cancelled)
                stopped.set(true)
                stopTailers(outTailer, errTailer)
                listener.onFinished(
                    ExecResult(
                        exitCode = exitCode,
                        stdout = state.stdout.text(),
                        stderr = state.stderr.text(),
                        durationMs = System.currentTimeMillis() - startedAt,
                        truncated = state.stdout.truncated || state.stderr.truncated,
                    ),
                )
                exitCode
            } catch (t: Throwable) {
                stopped.set(true)
                stopTailers(outTailer, errTailer)
                listener.onFailed(t)
                1
            } finally {
                running.set(false)
                runDir.deleteRecursively()
            }
        })

        return object : ExecutionHandle {
            override val isRunning: Boolean get() = running.get() && !future.isDone
            override fun cancel() { cancelled.set(true) }
        }
    }

    /**
     * Runs [request] to completion (waiting for the worker) and returns the
     * result. On timeout the run is flagged cancelled and a 124 result is
     * returned. Used by the agent's bash tool and the editor.
     */
    fun executeSync(request: ExecRequest, timeoutMs: Long): ExecResult {
        val done = CompletableFuture<ExecResult>()
        val handle = execute(request, object : OutputListener {
            override fun onFinished(result: ExecResult) { done.complete(result) }
            override fun onFailed(error: Throwable) { done.completeExceptionally(error) }
        })
        return try {
            done.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            handle.cancel()
            ExecResult(124, "", "превышено время выполнения (${timeoutMs / 1000} с)", timeoutMs)
        } catch (e: ExecutionException) {
            ExecResult(1, "", e.cause?.message ?: e.message ?: "ошибка запуска", 0)
        }
    }

    // ---------------------------------------------------------------- engine

    /**
     * One QuickJS instance per run, driven to completion on the worker thread.
     *
     * [runBlocking] is not a compromise here: the worker exists precisely so a run
     * owns a thread for its whole life, and the JS job dispatcher inside the engine
     * needs a free pool to resolve promises on while this one waits.
     */
    private fun runLocked(state: RunState, cancelled: AtomicBoolean): Int = runBlocking {
        val js = QuickJs.create(Dispatchers.Default)
        try {
            js.memoryLimit = MAX_HEAP_BYTES
            js.maxStackSize = MAX_STACK_BYTES
            js.evaluationTimeoutMillis = maxRunMs
            installBridge(js, state)
            js.eval(bootSource, "boot.js")

            // Entry module resolves relative requires against its own directory.
            val target = state.request.target
            if (target.startsWith("/")) {
                val dir = target.substringBeforeLast('/', "")
                js.eval("__setMainDir(${JSONObject.quote(dir)})", "runtime")
            }

            var exitCode = try {
                js.eval(readMainSource(state.request), state.request.target)
                0
            } catch (e: QuickJsException) {
                reportJsError(state, e)
                1
            }

            // Event loop: driven by the boot script's hooks.
            val loopStart = System.currentTimeMillis()
            while (true) {
                if (System.currentTimeMillis() - loopStart > maxRunMs) {
                    if (exitCode == 0) exitCode = 124
                    writeErr(state, "run timeout after ${maxRunMs / 1000}s\n")
                    break
                }
                val exit = js.eval("__prExitCode()", "eventloop").asNumberOrNull()
                if (exit != null) {
                    exitCode = exit.toInt()
                    break
                }
                if (cancelled.get()) { exitCode = 130; break }
                if (js.eval("__prPending()", "eventloop").asNumber() <= 0.0) break
                val next = js.eval("__prNextDue()", "eventloop").asNumber()
                val now = System.currentTimeMillis()
                if (next > now + 1.0) {
                    try {
                        Thread.sleep((next - now).coerceAtMost(20.0).toLong())
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        if (exitCode == 0) exitCode = 130
                        break
                    }
                    continue
                }
                try {
                    js.eval("__prRunDue()", "eventloop")
                } catch (e: QuickJsException) {
                    // boot.js already isolates per-callback failures; this is a last resort
                    writeErr(state, "event loop error: ${e.message}\n")
                    if (exitCode == 0) exitCode = 1
                    break
                }
            }
            runCatching { js.eval("__prOnExit()", "eventloop") }
            state.outWriter.flush()
            state.errWriter.flush()
            exitCode
        } finally {
            runCatching { state.outWriter.flush(); state.errWriter.flush() }
            runCatching { js.close() }
        }
    }

    /**
     * A script error, formatted the way the runtime has always reported it so the
     * model sees the same "ERROR at line N in file" it learned to parse.
     */
    private fun reportJsError(state: RunState, e: QuickJsException) {
        val where = e.lineNumber?.let { "ERROR at line $it in ${e.fileName ?: "script"}" }
            ?: "ERROR in ${e.fileName ?: "script"}"
        writeErr(state, "$where: ${e.message}\n${e.stack ?: ""}\n")
    }

    /**
     * Evaluates [code] and returns its value.
     *
     * Always `Any?` on purpose: QuickJS hands back whole numbers as `Long`, and a
     * declared `Double`/`String` target makes the engine throw on the conversion
     * rather than on the script. `undefined` arrives as `null`, which is also what
     * a Kotlin `null` becomes on the way in - boot.js checks `=== null` to mean
     * "no such file", so that distinction has to survive.
     */
    private suspend fun QuickJs.eval(code: String, file: String): Any? =
        evaluate<Any?>(code, file, false)

    /**
     * The script to evaluate, sandboxed: `require` is already gated by
     * [sandboxFile], and the entry point needs the same check - otherwise a
     * script outside the workspace (a path from a stale run, a package `bin`
     * pointing at `../../../shared_prefs/…`) would be evaluated as JavaScript.
     */
    private fun readMainSource(request: ExecRequest): String {
        val file = workspace.resolve(request.target, request.cwd ?: workspace.root)
            ?: throw IllegalStateException("путь вне рабочей папки отклонён: ${request.target}")
        if (!file.isFile) throw IllegalStateException("скрипт не найден: ${request.target}")
        // Bin scripts start with a shebang; JavaScript has no '#' comments.
        return file.readText(Charsets.UTF_8).replace(Regex("^#![^\\n]*\\n?"), "")
    }

    private fun Any?.asNumber(): Double = (this as? Number)?.toDouble() ?: 0.0

    /** Null for `undefined` and for a real null, so "no exit code" stays distinct from 0. */
    private fun Any?.asNumberOrNull(): Double? = (this as? Number)?.toDouble()

    private fun stopTailers(outTailer: OutputTailer, errTailer: OutputTailer) {
        // The tailers watch log files that this thread just closed; give them a
        // moment to drain. (OutputTailer stops when the run dir disappears.)
        try { outTailer.join(TAILER_JOIN_MS) } catch (_: InterruptedException) {}
        try { errTailer.join(TAILER_JOIN_MS) } catch (_: InterruptedException) {}
    }

    // ---------------------------------------------------------------- bridge

    /**
     * Installs the `__pr` object with every function boot.js calls.
     *
     * The scope QuickJS hands a script is already its own realm: there is no Java
     * object graph reachable from it, only values crossing this boundary.
     */
    private fun installBridge(js: QuickJs, state: RunState) {
        fun str(args: Array<out Any?>, i: Int): String? = args.getOrNull(i)?.toString()

        js.define("__pr") {
            function("root") { _ -> workspace.root.absolutePath }
            function("now") { _ -> System.currentTimeMillis() }

            function("print") { a -> str(a, 0)?.let { writeOut(state, it) } }
            function("printErr") { a -> str(a, 0)?.let { writeErr(state, it) } }

            function("argv") {
                listOf("node", state.request.target) + state.request.args
            }
            function("env") {
                listOf(
                    "HOME=${workspace.root.absolutePath}",
                    "PATH=/usr/local/bin:/usr/bin:/bin",
                    "LANG=C.UTF-8",
                    "NODE_ENV=production",
                    "TMPDIR=${File(workspace.cache, "tmp").apply { mkdirs() }.absolutePath}",
                )
            }

            function("cwd") { _ -> state.cwd.absolutePath }
            function("chdir") { a ->
                val d = str(a, 0) ?: return@function false
                val resolved = workspace.resolve(d, state.cwd) ?: return@function false
                if (resolved.isDirectory) { state.cwd = resolved; true } else false
            }

            function("fsRead") { a ->
                val f = sandboxFile(str(a, 0)) ?: return@function null
                if (!f.isFile || f.length() > MAX_READ) null else f.readText(Charsets.UTF_8)
            }
            function("fsWrite") { a ->
                val f = sandboxFile(str(a, 0)) ?: return@function false
                try {
                    f.parentFile?.mkdirs()
                    f.writeText(str(a, 1) ?: "", Charsets.UTF_8)
                    true
                } catch (t: Throwable) { false }
            }
            function("fsAppend") { a ->
                val f = sandboxFile(str(a, 0)) ?: return@function false
                try {
                    f.parentFile?.mkdirs()
                    f.appendText(str(a, 1) ?: "", Charsets.UTF_8)
                    true
                } catch (t: Throwable) { false }
            }
            function("fsExists") { a -> sandboxFile(str(a, 0))?.exists() == true }
            function("fsIsDir") { a -> sandboxFile(str(a, 0))?.isDirectory == true }
            function("fsList") { a ->
                val f = sandboxFile(str(a, 0)) ?: return@function null
                if (!f.isDirectory) return@function null
                f.list()?.sorted() ?: emptyList<String>()
            }
            function("fsSize") { a ->
                (sandboxFile(str(a, 0))?.takeIf { it.isFile }?.length() ?: 0L)
            }
            function("fsMtime") { a ->
                (sandboxFile(str(a, 0))?.takeIf { it.exists() }?.lastModified() ?: 0L)
            }
            function("fsMkdir") { a ->
                val f = sandboxFile(str(a, 0)) ?: return@function false
                try { f.mkdirs(); true } catch (t: Throwable) { false }
            }
            function("fsDelete") { a ->
                val f = sandboxFile(str(a, 0)) ?: return@function false
                f.isFile && f.delete()
            }
            function("fsRename") { a ->
                val from = sandboxFile(str(a, 0)) ?: return@function false
                val to = sandboxFile(str(a, 1)) ?: return@function false
                try {
                    to.parentFile?.mkdirs()
                    from.renameTo(to)
                } catch (t: Throwable) { false }
            }

            function("http") { a ->
                val method = str(a, 0)?.uppercase() ?: "GET"
                val url = str(a, 1) ?: return@function null
                val headers = str(a, 2) ?: "{}"
                val body = str(a, 3)
                httpBridge(method, url, headers, body)
            }
        }
    }

    /** Resolves an absolute path from boot.js against the sandbox; null if it escapes. */
    private fun sandboxFile(abs: String?): File? {
        if (abs == null) return null
        return workspace.resolve(abs)
    }

    private fun writeOut(state: RunState, text: String) {
        try {
            state.outWriter.write(text)
            state.outWriter.flush()
        } catch (t: Throwable) { Log.w(TAG, "stdout write failed", t) }
    }

    private fun writeErr(state: RunState, text: String) {
        try {
            state.errWriter.write(text)
            state.errWriter.flush()
        } catch (t: Throwable) { Log.w(TAG, "stderr write failed", t) }
    }

    /**
     * Synchronous http(s) for the boot.js `http`/`https` modules. Returns
     * `{"status":…,"headers":{…},"body":"…"}` as JSON text, or null on any
     * failure (boot.js turns that into a request 'error' event).
     */
    private fun httpBridge(method: String, url: String, headersJson: String, body: String?): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            runCatching { JSONObject(headersJson) }.getOrNull()?.let { h ->
                for (key in h.keys()) conn.setRequestProperty(key, h.getString(key))
            }
            // An empty string means "no body" (boot.js passes '' for GET): setting
            // doOutput on a GET silently turns it into POST in HttpURLConnection.
            if (!body.isNullOrEmpty()) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Length", body.toByteArray(Charsets.UTF_8).size.toString())
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val text = readCapped(conn, status)
            val headers = JSONObject()
            for ((k, v) in conn.headerFields) {
                if (k != null && !v.isNullOrEmpty()) headers.put(k, v.joinToString(", "))
            }
            JSONObject()
                .put("status", status)
                .put("headers", headers)
                .put("body", text)
                .toString()
        } catch (t: Throwable) {
            Log.w(TAG, "http $method $url failed: ${t.message}")
            null
        }
    }

    private fun readCapped(conn: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..399) conn.inputStream else conn.errorStream ?: return ""
        return stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (total < MAX_HTTP_BODY) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toString("UTF-8")
        }
    }

    /** Forwards tailer output and keeps a capped copy for the final ExecResult. */
    private class CaptureListener(
        private val downstream: OutputListener,
        private val capture: Capture,
    ) : OutputListener {
        override fun onOutput(stream: OutputStream, text: String) {
            capture.append(text)
            downstream.onOutput(stream, text)
        }
    }

    private class Capture {
        private val builder = StringBuilder()
        var truncated = false
            private set

        fun append(text: String) {
            if (truncated) return
            val room = MAX_CAPTURE - builder.length
            if (room <= 0) { truncated = true; return }
            if (text.length <= room) builder.append(text) else {
                builder.append(text, 0, room)
                truncated = true
            }
        }

        fun text(): String = builder.toString()
    }
}
