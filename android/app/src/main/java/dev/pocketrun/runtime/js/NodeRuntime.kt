package dev.pocketrun.runtime.js

import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.ExecResult
import dev.pocketrun.runtime.ExecutionHandle
import dev.pocketrun.runtime.OutputListener
import dev.pocketrun.runtime.OutputStream
import dev.pocketrun.runtime.RuntimeKind
import dev.pocketrun.runtime.SyncScriptRuntime
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Native Node.js: nodejs-mobile ships real Node 18 as `libnode.so` but no
 * executable. The build packages a tiny launcher built on top of it as
 * `libnoderunner.so` in `jniLibs`, so Android extracts it to the app's
 * `nativeLibraryDir` where it can be exec'd. Each script becomes a real,
 * isolated, cancellable Node process.
 *
 * This is the primary NODE runtime on device; [JsRuntime] (Rhino) stays as the
 * fallback when the native launcher is missing (e.g. JVM unit tests).
 */
class NodeRuntime(
    nativeLibraryDir: File,
    private val workspace: Workspace,
    private val maxRunMs: Long = 10 * 60_000L,
) : SyncScriptRuntime {

    companion object {
        private const val MAX_CAPTURE = 256 * 1024
        const val VERSION = "Node.js 18.20.4 (nodejs-mobile)"
    }

    private val launcher = File(nativeLibraryDir, "libnoderunner.so")

    override val kind: RuntimeKind = RuntimeKind.NODE
    override val version: String = VERSION

    override fun isAvailable(): Boolean = launcher.isFile

    override fun execute(request: ExecRequest, listener: OutputListener): ExecutionHandle {
        val startedAt = System.currentTimeMillis()
        val running = AtomicBoolean(true)
        val cancelled = AtomicBoolean(false)
        val process = AtomicReference<Process?>(null)

        val thread = Thread({
            val out = StringBuilder()
            val err = StringBuilder()
            try {
                val p = launch(request)
                process.set(p)
                if (cancelled.get()) p.destroy()

                val outReader = Thread {
                    runCatching {
                        p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                            lines.forEach { line ->
                                appendCapped(out, line + "\n")
                                listener.onOutput(OutputStream.STDOUT, line + "\n")
                            }
                        }
                    }
                }
                val errReader = Thread {
                    runCatching {
                        p.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                            lines.forEach { line ->
                                appendCapped(err, line + "\n")
                                listener.onOutput(OutputStream.STDERR, line + "\n")
                            }
                        }
                    }
                }
                outReader.start()
                errReader.start()

                request.stdin?.let { input ->
                    runCatching { p.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(input) } }
                }

                val exit = p.waitFor()
                outReader.join(2_000)
                errReader.join(2_000)
                if (!cancelled.get()) {
                    listener.onFinished(
                        ExecResult(
                            exitCode = exit,
                            stdout = out.toString(),
                            stderr = err.toString(),
                            durationMs = System.currentTimeMillis() - startedAt,
                            truncated = out.length >= MAX_CAPTURE || err.length >= MAX_CAPTURE,
                        ),
                    )
                }
            } catch (t: Throwable) {
                if (!cancelled.get()) listener.onFailed(t)
            } finally {
                running.set(false)
            }
        }, "pocketrun-node-run").apply { isDaemon = true }
        thread.start()

        return object : ExecutionHandle {
            override val isRunning: Boolean get() = running.get()
            override fun cancel() {
                cancelled.set(true)
                process.get()?.let { runCatching { it.destroy() } }
            }
        }
    }

    override fun executeSync(request: ExecRequest, timeoutMs: Long): ExecResult {
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

    private fun launch(request: ExecRequest): Process {
        val script = File(request.target)
        check(script.isFile) { "скрипт не найден: ${request.target}" }
        val cwd = request.cwd?.takeIf { it.isDirectory } ?: workspace.root

        val command = ArrayList<String>(request.args.size + 2)
        command.add(launcher.absolutePath)
        command.add(script.absolutePath)
        command.addAll(request.args)

        val pb = ProcessBuilder(command)
        pb.directory(cwd)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = launcher.parentFile?.absolutePath ?: ""
        env["HOME"] = workspace.root.absolutePath
        env["NODE_PATH"] = listOf(
            File(workspace.packages, "node_modules").absolutePath,
            workspace.packages.absolutePath,
        ).joinToString(":")
        return pb.start()
    }

    private fun appendCapped(sb: StringBuilder, text: String) {
        if (sb.length >= MAX_CAPTURE) return
        sb.append(text, 0, minOf(text.length, MAX_CAPTURE - sb.length))
    }
}
