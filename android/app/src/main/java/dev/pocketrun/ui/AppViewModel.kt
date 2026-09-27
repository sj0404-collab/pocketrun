package dev.pocketrun.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.pocketrun.BuildConfig
import dev.pocketrun.agent.Agent
import dev.pocketrun.agent.AgentSettings
import dev.pocketrun.agent.AgentTools
import dev.pocketrun.agent.LlmClient
import dev.pocketrun.core.Workspace
import dev.pocketrun.license.LicenseManager
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.ExecutionHandle
import dev.pocketrun.runtime.OutputListener
import dev.pocketrun.runtime.OutputStream
import dev.pocketrun.runtime.RuntimeKind
import dev.pocketrun.runtime.js.JsRuntime
import dev.pocketrun.runtime.npm.NpxRuntime
import dev.pocketrun.runtime.python.PythonRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One screen-full of state: license, project list, the script being edited,
 * live run output and the agent conversation. Everything observable is a
 * StateFlow so Compose can collect it directly; everything mutating runs
 * through small methods.
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

    /** Which tab of the editor is active: Python source, Node source or an npx command. */
    val editorMode = MutableStateFlow(RuntimeKind.PYTHON)
    val editorModeFlow: StateFlow<RuntimeKind> = editorMode.asStateFlow()

    private val _script = MutableStateFlow("")
    val script: StateFlow<String> = _script.asStateFlow()

    /** The npx command line (package + arguments) for the NPX editor tab. */
    val npxCommand = MutableStateFlow("cowsay@1.4.0 Привет, PocketRun!")
    val npxCommandFlow: StateFlow<String> = npxCommand.asStateFlow()

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

    // ---------------------------------------------------------------- runtimes

    val jsRuntime: JsRuntime by lazy {
        val boot = getApplication<Application>().assets.open("node/boot.js")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        JsRuntime(boot, workspace)
    }

    val npxRuntime: NpxRuntime by lazy { NpxRuntime(jsRuntime, workspace) }

    // ---------------------------------------------------------------- agent

    /** One bubble / row in the agent conversation. */
    sealed interface AgentItem {
        data class User(val text: String) : AgentItem
        data class Assistant(val text: String) : AgentItem
        data class Tool(val name: String, val args: String, val result: String?) : AgentItem
        data class Error(val text: String) : AgentItem
        data class Info(val text: String) : AgentItem
    }

    private val _agentMessages = MutableStateFlow<List<AgentItem>>(emptyList())
    val agentMessages: StateFlow<List<AgentItem>> = _agentMessages.asStateFlow()

    private val _agentRunning = MutableStateFlow(false)
    val agentRunning: StateFlow<Boolean> = _agentRunning.asStateFlow()

    private val _llmConfig = MutableStateFlow(AgentSettings.read(app))
    val llmConfig: StateFlow<AgentSettings.Config> = _llmConfig.asStateFlow()

    private val agentExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pocketrun-agent").apply { isDaemon = true } }

    private var agentHistory = JSONArray()
    private val agentCancelled = AtomicBoolean(false)

    fun canUseAgent(): Boolean =
        (licenseState.value as? LicenseManager.LicenseState.Active)?.canUseAgent == true

    fun saveLlmConfig(config: AgentSettings.Config) {
        AgentSettings.save(getApplication(), config)
        _llmConfig.value = config
    }

    fun clearAgentChat() {
        if (_agentRunning.value) return
        agentHistory = JSONArray()
        _agentMessages.value = emptyList()
    }

    fun sendToAgent(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _agentRunning.value) return
        if (!canUseAgent()) {
            _agentMessages.update { it + AgentItem.Error("Агент доступен на плане Pro — активируйте Pro-лицензию.") }
            return
        }
        val config = _llmConfig.value
        if (!config.isReady) {
            _agentMessages.update { it + AgentItem.Info("Сначала настройте модель: базовый URL, API-ключ и имя модели (кнопка ⚙ над чатом).") }
            return
        }
        _agentMessages.update { it + AgentItem.User(prompt) }
        _agentRunning.value = true
        agentCancelled.set(false)

        agentExecutor.execute {
            val tools = AgentTools(workspace, jsRuntime, npxRuntime)
            val agent = Agent(tools, { LlmClient(config) })
            agent.cancelled = agentCancelled.get()
            val newHistory = agent.run(prompt, agentHistory) { event ->
                when (event) {
                    is Agent.Event.AssistantText ->
                        _agentMessages.update { it + AgentItem.Assistant(event.text) }
                    is Agent.Event.ToolStart ->
                        _agentMessages.update { it + AgentItem.Tool(event.name, event.args, null) }
                    is Agent.Event.ToolDone ->
                        _agentMessages.update { items ->
                            val idx = items.indexOfLast { it is AgentItem.Tool && it.result == null && it.name == event.name }
                            if (idx >= 0) items.toMutableList().also { list -> list[idx] = (list[idx] as AgentItem.Tool).copy(result = event.result) }
                            else items + AgentItem.Tool(event.name, "", event.result)
                        }
                    is Agent.Event.Failed ->
                        _agentMessages.update { it + AgentItem.Error(event.message) }
                }
            }
            agentHistory = newHistory
            _agentRunning.value = false
        }
    }

    fun cancelAgent() {
        agentCancelled.set(true)
    }

    // ---------------------------------------------------------------- projects

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
        loadScriptForMode()
    }

    fun setEditorMode(mode: RuntimeKind) {
        editorMode.value = mode
        if (_selected.value != null) loadScriptForMode()
    }

    private fun loadScriptForMode() {
        val project = _selected.value ?: return
        when (editorMode.value) {
            RuntimeKind.NODE -> {
                val jsFile = File(project.dir, "main.js")
                _script.value = jsFile.takeIf { it.isFile }?.readText(Charsets.UTF_8) ?: DEFAULT_JS_SCRIPT
            }
            else -> {
                _script.value = File(project.dir, "main.py")
                    .takeIf { it.isFile }
                    ?.readText(Charsets.UTF_8)
                    ?: DEFAULT_SCRIPT
            }
        }
    }

    /** Saves the editor content under the active mode's file name. */
    fun saveScript(text: String) {
        val project = _selected.value ?: return
        when (editorMode.value) {
            RuntimeKind.NODE -> File(project.dir, "main.js").writeText(text, Charsets.UTF_8)
            else -> File(project.dir, "main.py").writeText(text, Charsets.UTF_8)
        }
    }

    fun runtimeAvailable(): Boolean = when (editorMode.value) {
        RuntimeKind.NODE, RuntimeKind.NPX -> true
        else -> PythonRuntime.isAvailable()
    }

    fun runtimeVersion(): String? = when (editorMode.value) {
        RuntimeKind.NODE, RuntimeKind.NPX -> JsRuntime.VERSION
        else -> PythonRuntime.version()
    }

    fun runScript(text: String) {
        val project = _selected.value ?: return
        if (_runState.value is RunState.Running) return
        when (editorMode.value) {
            RuntimeKind.NODE -> {
                saveScript(text)
                _output.value = emptyList()
                _runState.value = RunState.Running
                handle = jsRuntime.execute(
                    ExecRequest(
                        kind = RuntimeKind.NODE,
                        target = File(project.dir, "main.js").absolutePath,
                        args = emptyList(),
                        cwd = project.dir,
                    ),
                    runListener,
                )
            }
            RuntimeKind.PYTHON -> {
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
                    ),
                    runListener,
                )
            }
            RuntimeKind.NPX -> {
                // The npx tab runs through runNpxCommand with its own command line.
            }
        }
    }

    fun runNpxCommand(line: String) {
        if (_runState.value is RunState.Running) return
        val parsed = NpxRuntime.parseCommandLine(line)
        if (parsed == null) {
            _runState.value = RunState.Failed("Не удалось разобрать команду — ожидается «пакет[@версия] [аргументы]»")
            return
        }
        val (spec, args) = parsed
        _output.value = emptyList()
        _runState.value = RunState.Running
        handle = npxRuntime.execute(
            ExecRequest(
                kind = RuntimeKind.NPX,
                target = spec.name + (spec.version?.let { "@$it" } ?: ""),
                args = args,
                cwd = _selected.value?.dir ?: workspace.root,
            ),
            runListener,
        )
    }

    /**
     * Cancellation is cooperative: the interpreter finishes the current script
     * and the run is reported with exit code 130.
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

        val DEFAULT_JS_SCRIPT = """
            // Node-совместимый слой PocketRun: require/fs/timers/Buffer/http доступны.
            const os = require('os');

            console.log('Привет из Node-слоя PocketRun!');
            console.log('Платформа:', os.platform());

            [1, 2, 3, 4, 5].forEach(function (n) {
                console.log(n + ' * ' + n + ' = ' + n * n);
            });

            setTimeout(function () {
                console.log('Это напечатано из таймера через 300 мс.');
                console.log('Готово.');
            }, 300);
        """.trimIndent()
    }
}
