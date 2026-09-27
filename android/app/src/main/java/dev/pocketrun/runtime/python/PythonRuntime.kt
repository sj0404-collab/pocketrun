package dev.pocketrun.runtime.python

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.ExecResult
import dev.pocketrun.runtime.ExecutionHandle
import dev.pocketrun.runtime.OutputListener
import dev.pocketrun.runtime.OutputTailer
import dev.pocketrun.runtime.OutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The CPython runtime. Chaquopy keeps one interpreter per process, so it is
 * started once from [initAsync] (about a second; never on the main thread) and
 * every [execute] afterwards reuses it.
 *
 * Scripts talk to the UI through files, not pipes: the Python bootstrap
 * (pocketrun.py) appends stdout/stderr to log files in the workspace cache and
 * two [OutputTailer]s stream them back as text.
 */
object PythonRuntime {

    private const val TAG = "PythonRuntime"
    private const val MAX_CAPTURE = 256 * 1024
    private const val TAILER_JOIN_MS = 2_000L

    private val lock = Any()

    @Volatile private var python: Python? = null
    @Volatile private var version: String? = null
    @Volatile private var runsDir: File? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pocketrun-python-run").apply { isDaemon = true }
    }

    /** Starts CPython once. Safe to call again; returns immediately. */
    fun initAsync(context: Context) {
        if (python != null) return
        Thread({
            try {
                synchronized(lock) {
                    if (python == null) {
                        val appContext = context.applicationContext
                        Python.start(AndroidPlatform(appContext))
                        val py = Python.getInstance()
                        runsDir = File(Workspace.from(appContext).cache, "runs").apply { mkdirs() }
                        version = readVersion(py)
                        python = py
                        Log.i(TAG, "Python started ($version)")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Python failed to start", t)
            }
        }, "pocketrun-python-start").apply { isDaemon = true }.start()
    }

    fun isAvailable(): Boolean = python != null

    fun version(): String? = version

    private fun readVersion(py: Python): String? = try {
        val info = py.getModule("pocketrun").callAttr("interpreter_info").asMap()
        info["version"] as? String
    } catch (t: Throwable) {
        Log.w(TAG, "interpreter_info failed", t)
        null
    }

    /**
     * Runs [request.target] with CPython. [listener.onOutput] arrives from the
     * tailer threads; [listener.onFinished] / [listener.onFailed] arrive from
     * the single run thread.
     *
     * Cancellation is cooperative: Chaquopy cannot interrupt a call, so
     * [ExecutionHandle.cancel] only raises a flag, and the result is reported
     * with exit code 130 (SIGINT) once the script returns on its own.
     */
    fun execute(request: ExecRequest, listener: OutputListener): ExecutionHandle {
        val py = python
        val stopped = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val running = AtomicBoolean(true)
        if (py == null) {
            running.set(false)
            listener.onFailed(IllegalStateException("Python is not running (yet)"))
            return Handle(running, cancelled)
        }

        val startedAt = System.currentTimeMillis()
        val runDir = File(runsDir ?: File(request.cwd, ".runs"), UUID.randomUUID().toString())
            .apply { mkdirs() }
        val outFile = File(runDir, "out.log")
        val errFile = File(runDir, "err.log")
        val stdout = Capture()
        val stderr = Capture()

        val outTailer = OutputTailer(outFile, OutputStream.STDOUT, CaptureListener(listener, stdout), stopped) {}
        val errTailer = OutputTailer(errFile, OutputStream.STDERR, CaptureListener(listener, stderr), stopped) {}
        outTailer.start()
        errTailer.start()

        executor.execute {
            try {
                val argsJson = org.json.JSONArray().apply { request.args.forEach { put(it) } }.toString()
                val exitCode = py.getModule("pocketrun").callAttr(
                    "run",
                    request.target,
                    argsJson,
                    request.stdin,
                    outFile.absolutePath,
                    errFile.absolutePath,
                    request.cwd?.absolutePath,
                ).toInt()

                stopped.set(true)
                outTailer.join(TAILER_JOIN_MS)
                errTailer.join(TAILER_JOIN_MS)

                listener.onFinished(
                    ExecResult(
                        exitCode = if (cancelled.get()) 130 else exitCode,
                        stdout = stdout.text(),
                        stderr = stderr.text(),
                        durationMs = System.currentTimeMillis() - startedAt,
                        truncated = stdout.truncated || stderr.truncated,
                    ),
                )
            } catch (t: Throwable) {
                stopped.set(true)
                try {
                    outTailer.join(TAILER_JOIN_MS)
                    errTailer.join(TAILER_JOIN_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                listener.onFailed(t)
            } finally {
                running.set(false)
                runDir.deleteRecursively()
            }
        }
        return Handle(running, cancelled)
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
            if (room <= 0) {
                truncated = true
                return
            }
            if (text.length <= room) {
                builder.append(text)
            } else {
                builder.append(text, 0, room)
                truncated = true
            }
        }

        fun text(): String = builder.toString()
    }

    private class Handle(
        private val running: AtomicBoolean,
        private val cancelled: AtomicBoolean,
    ) : ExecutionHandle {
        override val isRunning: Boolean get() = running.get()
        override fun cancel() = cancelled.set(true)
    }
}
