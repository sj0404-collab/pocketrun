package dev.pocketrun.runtime

import java.io.File

enum class RuntimeKind(val id: String, val label: String) {
    PYTHON("python", "Python"),
    NODE("node", "Node"),
    NPX("npx", "npx"),
    ;

    companion object {
        fun from(id: String): RuntimeKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A single execution. [target] is a script path for PYTHON/NODE and a package
 * specifier for NPX; [args] are the arguments after the script name.
 */
data class ExecRequest(
    val kind: RuntimeKind,
    val target: String,
    val args: List<String> = emptyList(),
    val cwd: File? = null,
    val stdin: String? = null,
)

data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val truncated: Boolean = false,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

enum class OutputStream { STDOUT, STDERR }

/** Called from the runtime's worker thread; implementations must not block. */
interface OutputListener {
    fun onOutput(stream: OutputStream, text: String) {}
    fun onFinished(result: ExecResult) {}
    fun onFailed(error: Throwable) {}
}

interface ExecutionHandle {
    val isRunning: Boolean
    fun cancel()
}

/** One language runtime inside the app. */
interface ScriptRuntime {
    val kind: RuntimeKind
    val version: String
    fun isAvailable(): Boolean
    fun execute(request: ExecRequest, listener: OutputListener): ExecutionHandle
}
