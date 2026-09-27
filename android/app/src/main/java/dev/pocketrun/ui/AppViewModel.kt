package dev.pocketrun.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.pocketrun.BuildConfig
import dev.pocketrun.core.Workspace
import dev.pocketrun.license.LicenseManager
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.ExecutionHandle
import dev.pocketrun.runtime.OutputListener
import dev.pocketrun.runtime.OutputStream
import dev.pocketrun.runtime.RuntimeKind
import dev.pocketrun.runtime.python.PythonRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * One screen-full of state: license, project list, the script being edited and
 * the live run output. Everything observable is a StateFlow so Compose can
 * collect it directly; everything mutating runs through small methods.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val licenseManager = LicenseManager(app, BuildConfig.LICENSE_PUBKEY)
    val licenseState: StateFlow<LicenseManager.LicenseState> = licenseManager.state

    private val workspace = Workspace.from(app)

    data class Project(val name: String, val dir: File)

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _selected = MutableStateFlow<Project?>(null)
    val selected: StateFlow<Project?> = _selected.asStateFlow()

    private val _script = MutableStateFlow("")
    val script: StateFlow<String> = _script.asStateFlow()

    sealed interface RunState {
        data object Idle : RunState
        data object Running : RunState
        data class Finished(val exitCode: Int, val durationMs: Long) : RunState
        data class Failed(val message: String) : RunState
    }

    data class OutputLine(val stream: OutputStream, val text: String)

    private val _runState = MutableStateFlow<RunState>(RunState.Idle)
    val runState: StateFlow<RunState> = _runState.asStateFlow()

    private val _output = MutableStateFlow<List<OutputLine>>(emptyList())
    val output: StateFlow<List<OutputLine>> = _output.asStateFlow()

    private var handle: ExecutionHandle? = null

    private val runListener = object : OutputListener {
        override fun onOutput(stream: OutputStream, text: String) {
            _output.update { lines -> (lines + OutputLine(stream, text)).takeLast(MAX_OUTPUT_LINES) }
        }

        override fun onFinished(result: dev.pocketrun.runtime.ExecResult) {
            _runState.value = RunState.Finished(result.exitCode, result.durationMs)
        }

        override fun onFailed(error: Throwable) {
            _runState.value = RunState.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    init {
        refreshProjects()
        if (_projects.value.isEmpty()) createProject("hello")
    }

    fun refreshProjects() {
        _projects.value = workspace.projects
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .map { Project(it.name, it) }
            .sortedBy { it.name.lowercase() }
    }

    fun createProject(rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        val dir = workspace.projectDir(name)
        val main = File(dir, "main.py")
        if (!main.isFile) main.writeText(DEFAULT_SCRIPT, Charsets.UTF_8)
        refreshProjects()
        _projects.value.firstOrNull { it.dir == dir }?.let { select(it) }
    }

    fun deleteProject(project: Project) {
        project.dir.deleteRecursively()
        if (_selected.value?.dir == project.dir) {
            _selected.value = null
            _script.value = ""
        }
        refreshProjects()
    }

    fun select(project: Project) {
        _selected.value = project
        _script.value = File(project.dir, "main.py")
            .takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?: DEFAULT_SCRIPT
    }

    fun saveScript(text: String) {
        val project = _selected.value ?: return
        File(project.dir, "main.py").writeText(text, Charsets.UTF_8)
    }

    fun runtimeAvailable(): Boolean = PythonRuntime.isAvailable()

    fun runtimeVersion(): String? = PythonRuntime.version()

    fun runScript(text: String) {
        val project = _selected.value ?: return
        if (_runState.value is RunState.Running) return
        if (!PythonRuntime.isAvailable()) {
            _runState.value = RunState.Failed("Python ещё запускается — попробуйте через пару секунд")
            return
        }
        saveScript(text)
        _output.value = emptyList()
        _runState.value = RunState.Running
        handle = PythonRuntime.execute(
            ExecRequest(
                kind = RuntimeKind.PYTHON,
                target = File(project.dir, "main.py").absolutePath,
                args = emptyList(),
                cwd = project.dir,
                stdin = null,
            ),
            runListener,
        )
    }

    /**
     * Chaquopy cannot interrupt a running script; this only flags the run so it
     * is reported as cancelled (exit code 130) once it returns.
     */
    fun cancelRun() {
        handle?.cancel()
    }

    fun clearOutput() {
        _output.value = emptyList()
        _runState.value = RunState.Idle
    }

    fun activate(rawKey: String): Boolean = licenseManager.activate(rawKey)

    fun forgetLicense() = licenseManager.forget()

    companion object {
        private const val MAX_OUTPUT_LINES = 2_000

        val DEFAULT_SCRIPT = """
            import sys

            print("Привет из PocketRun!")
            print("Python:", sys.version.split()[0])

            for i in range(1, 6):
                print(f"{i} * {i} = {i * i}")

            print("Эта строка ушла в stderr.", file=sys.stderr)
            print("Готово.")
        """.trimIndent()
    }
}
