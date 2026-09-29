package dev.pocketrun.runtime.js

import android.util.Log
import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.ExecResult
import dev.pocketrun.runtime.ExecutionHandle
import dev.pocketrun.runtime.OutputListener
import dev.pocketrun.runtime.OutputTailer
import dev.pocketrun.runtime.OutputStream
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The JavaScript runtime: Rhino evaluating [bootSource] (node/boot.js from the
 * APK assets) into a fresh safe scope per run, plus the event loop the boot
 * script drives through its `__pr*` hooks.
 *
 * The bridge contract lives at the top of boot.js; this class is its only
 * implementation. Scripts reach the outside world only through the bridge, and
 * every filesystem call is contained inside the workspace root by [Workspace.resolve].
 *
 * Runs are serialized on one worker thread (Rhino contexts are not shareable);
 * output is streamed through per-run log files exactly like PythonRuntime.
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
        const val VERSION = "Rhino 1.8.1 (ES6,interpreted)"
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
     * returned. Used by the agent's run_node tool.
     */
    fun executeSync(request: ExecRequest, timeoutMs: Long): ExecResult {
        val done = java.util.concurrent.CompletableFuture<ExecResult>()
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

    private fun runLocked(state: RunState, cancelled: AtomicBoolean): Int {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            cx.optimizationLevel = -1 // no bytecode generation: required on Android
            val scope = cx.initSafeStandardObjects()
            installBridge(cx, scope, state)
            cx.evaluateString(scope, bootSource, "boot.js", 1, null)

            // Entry module resolves relative requires against its own directory.
            val target = state.request.target
            if (target.startsWith("/")) {
                val dir = target.substringBeforeLast('/', "")
                callGlobal(cx, scope, "__setMainDir") { arrayOf<Any?>(dir) }
            }

            var exitCode = try {
                val source = readMainSource(state.request)
                cx.evaluateString(scope, source, state.request.target, 1, null)
                0
            } catch (e: RhinoException) {
                state.errWriter.write(
                    "ERROR at line ${e.lineNumber()} in ${e.sourceName()}: ${e.details()}\n" +
                        e.getScriptStackTrace() + "\n",
                )
                state.errWriter.flush()
                1
            }

            // Event loop: driven by the boot script's hooks.
            val loopStart = System.currentTimeMillis()
            while (true) {
                if (System.currentTimeMillis() - loopStart > maxRunMs) {
                    if (exitCode == 0) exitCode = 124
                    state.errWriter.write("run timeout after ${maxRunMs / 1000}s\n"); state.errWriter.flush()
                    break
                }
                val ex = callGlobal(cx, scope, "__prExitCode") { arrayOfNulls(0) }
                if (ex != null && ex != Undefined.instance) {
                    exitCode = Context.toNumber(ex).toInt()
                    break
                }
                if (cancelled.get()) { exitCode = 130; break }
                val pending = Context.toNumber(callGlobal(cx, scope, "__prPending") { arrayOfNulls(0) })
                if (pending <= 0.0) break
                val next = Context.toNumber(callGlobal(cx, scope, "__prNextDue") { arrayOfNulls(0) })
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
                    callGlobal(cx, scope, "__prRunDue") { arrayOfNulls(0) }
                } catch (e: RhinoException) {
                    // boot.js already isolates per-callback failures; this is a last resort
                    state.errWriter.write("event loop error: ${e.details()}\n"); state.errWriter.flush()
                    if (exitCode == 0) exitCode = 1
                    break
                }
            }
            try {
                callGlobal(cx, scope, "__prOnExit") { arrayOfNulls(0) }
            } catch (_: Exception) { }
            state.outWriter.flush()
            state.errWriter.flush()
            return exitCode
        } finally {
            try { state.outWriter.flush(); state.errWriter.flush() } catch (_: Exception) {}
            Context.exit()
        }
    }

    /**
     * The script to evaluate, sandboxed: `require` is already gated by
     * [sandboxFile], and the entry point needs the same check — otherwise a
     * script outside the workspace (a path from a stale run, a package `bin`
     * pointing at `../../../shared_prefs/…`) would be evaluated as JavaScript.
     */
    private fun readMainSource(request: ExecRequest): String {
        val file = workspace.resolve(request.target, request.cwd ?: workspace.root)
            ?: throw IllegalStateException("путь вне рабочей папки отклонён: ${request.target}")
        if (!file.isFile) throw IllegalStateException("скрипт не найден: ${request.target}")
        // Bin scripts start with a shebang; Rhino has no '#' comments.
        return file.readText(Charsets.UTF_8).replace(Regex("^#![^\\n]*\\n?"), "")
    }

    private fun callGlobal(cx: Context, scope: Scriptable, name: String, args: () -> Array<Any?>): Any? {
        val fn = scope.get(name, scope)
        if (fn is Function) return fn.call(cx, scope, scope, args())
        return null
    }

    private fun stopTailers(outTailer: OutputTailer, errTailer: OutputTailer) {
        // The tailers watch log files that this thread just closed; give them a
        // moment to drain. (OutputTailer stops when the run dir disappears.)
        try { outTailer.join(TAILER_JOIN_MS) } catch (_: InterruptedException) {}
        try { errTailer.join(TAILER_JOIN_MS) } catch (_: InterruptedException) {}
    }

    // ---------------------------------------------------------------- bridge

    /**
     * Installs the `__pr` object with every function boot.js calls. Functions
     * are BaseFunction subclasses (NOT javaToJS): the safe scope must not see
     * arbitrary Java objects.
     */
    private fun installBridge(cx: Context, scope: Scriptable, state: RunState) {
        val bridge = cx.newObject(scope)
        ScriptableObject.putProperty(scope, "__pr", bridge)

        fun method(name: String, fn: (Array<Any?>) -> Any?) {
            ScriptableObject.putProperty(bridge, name, object : BaseFunction() {
                // NOTE: a Kotlin null here becomes JS null — boot.js checks
                // `=== null` for "no such file" and friends, so mapping null to
                // undefined would silently break those checks.
                override fun call(c: Context, s: Scriptable, thisObj: Scriptable, args: Array<Any?>?): Any? {
                    return fn(args ?: arrayOfNulls(0))
                }
            })
        }

        fun str(a: Array<Any?>, i: Int): String? = a.getOrNull(i)?.let { if (it === Undefined.instance) null else Context.toString(it) }

        method("root") { workspace.root.absolutePath }
        method("now") { System.currentTimeMillis().toDouble() }

        method("print") { a -> str(a, 0)?.let { writeOut(state, it) } }
        method("printErr") { a -> str(a, 0)?.let { writeErr(state, it) } }

        method("argv") {
            // Must be exactly Object[] for Context.newArray (see Rhino's checks).
            val list = buildList<Any?> {
                add("node")
                add(state.request.target)
                state.request.args.forEach { add(it) }
            }
            cx.newArray(scope, list.toTypedArray())
        }
        method("env") {
            val pairs = buildList<Any?> {
                add("HOME=${workspace.root.absolutePath}")
                add("PATH=/usr/local/bin:/usr/bin:/bin")
                add("LANG=C.UTF-8")
                add("NODE_ENV=production")
                add("TMPDIR=${File(workspace.cache, "tmp").apply { mkdirs() }.absolutePath}")
            }
            cx.newArray(scope, pairs.toTypedArray())
        }

        method("cwd") { state.cwd.absolutePath }
        method("chdir") { a ->
            val d = str(a, 0) ?: return@method false
            val resolved = workspace.resolve(d, state.cwd) ?: return@method false
            if (resolved.isDirectory) { state.cwd = resolved; true } else false
        }

        method("fsRead") { a ->
            val f = sandboxFile(str(a, 0)) ?: return@method null
            if (!f.isFile || f.length() > MAX_READ) null else f.readText(Charsets.UTF_8)
        }
        method("fsWrite") { a ->
            val f = sandboxFile(str(a, 0)) ?: return@method false
            try {
                f.parentFile?.mkdirs()
                f.writeText(str(a, 1) ?: "", Charsets.UTF_8)
                true
            } catch (t: Throwable) { false }
        }
        method("fsAppend") { a ->
            val f = sandboxFile(str(a, 0)) ?: return@method false
            try {
                f.parentFile?.mkdirs()
                f.appendText(str(a, 1) ?: "", Charsets.UTF_8)
                true
            } catch (t: Throwable) { false }
        }
        method("fsExists") { a -> sandboxFile(str(a, 0))?.exists() == true }
        method("fsIsDir") { a -> sandboxFile(str(a, 0))?.isDirectory == true }
        method("fsList") { a ->
            val f = sandboxFile(str(a, 0)) ?: return@method null
            if (!f.isDirectory) return@method null
            val names: Array<Any?> = f.list()?.sorted()?.map { it as Any? }?.toTypedArray()
                ?: arrayOfNulls<Any?>(0)
            cx.newArray(scope, names)
        }
        method("fsSize") { a ->
            val f = sandboxFile(str(a, 0))
            (f?.takeIf { it.isFile }?.length() ?: 0L).toDouble()
        }
        method("fsMtime") { a ->
            val f = sandboxFile(str(a, 0))
            (f?.takeIf { it.exists() }?.lastModified() ?: 0L).toDouble()
        }
        method("fsMkdir") { a ->
            val f = sandboxFile(str(a, 0)) ?: return@method false
            try { f.mkdirs(); true } catch (t: Throwable) { false }
        }
        method("fsDelete") { a ->
            val f = sandboxFile(str(a, 0)) ?: return@method false
            f.isFile && f.delete()
        }
        method("fsRename") { a ->
            val from = sandboxFile(str(a, 0)) ?: return@method false
            val to = sandboxFile(str(a, 1)) ?: return@method false
            try {
                to.parentFile?.mkdirs()
                from.renameTo(to)
            } catch (t: Throwable) { false }
        }

        method("http") { a ->
            val method = str(a, 0)?.uppercase() ?: "GET"
            val url = str(a, 1) ?: return@method null
            val headers = str(a, 2) ?: "{}"
            val body = str(a, 3)
            httpBridge(method, url, headers, body)
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
