package dev.pocketrun.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pocketrun.BuildConfig
import dev.pocketrun.agent.AgentSettings
import dev.pocketrun.agent.LlmClient
import dev.pocketrun.agent.opencode.Commands
import dev.pocketrun.agent.opencode.OpenCodeAgent
import dev.pocketrun.agent.opencode.OpenCodeConfig
import dev.pocketrun.agent.opencode.OpenCodeTools
import dev.pocketrun.agent.opencode.Sessions
import dev.pocketrun.core.ContentSearch
import dev.pocketrun.core.FileHistory
import dev.pocketrun.core.FileKind
import dev.pocketrun.core.FileNode
import dev.pocketrun.core.FileOps
import dev.pocketrun.core.FileText
import dev.pocketrun.core.FileTree
import dev.pocketrun.core.Mime
import dev.pocketrun.core.SearchHit
import dev.pocketrun.core.TreeFilter
import dev.pocketrun.core.Version
import dev.pocketrun.core.Workspace
import dev.pocketrun.license.LicenseManager
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.ExecutionHandle
import dev.pocketrun.runtime.OutputListener
import dev.pocketrun.runtime.OutputStream
import dev.pocketrun.runtime.RuntimeKind
import dev.pocketrun.runtime.SyncScriptRuntime
import dev.pocketrun.runtime.js.JsRuntime
import dev.pocketrun.runtime.js.NodeRuntime
import dev.pocketrun.runtime.npm.NpxRuntime
import dev.pocketrun.runtime.python.PythonRuntime
import dev.pocketrun.runtime.shell.Busybox
import dev.pocketrun.update.AppUpdate
import dev.pocketrun.update.AppUpdater
import dev.pocketrun.update.ReleaseInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One screen-full of state: license, project list, the script being edited,
 * live run output and the agent conversation. Everything observable is a
 * StateFlow so Compose can collect it directly; everything mutating runs
 * through small methods.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val licenseManager =
        LicenseManager(app, BuildConfig.LICENSE_PUBKEY, BuildConfig.SHARED_LICENSE_KEY)
    val licenseState: StateFlow<LicenseManager.LicenseState> = licenseManager.state

    private val workspace = Workspace.from(app)

    data class Project(val name: String, val dir: File)

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _selected = MutableStateFlow<Project?>(null)
    val selected: StateFlow<Project?> = _selected.asStateFlow()

    // -------------------------------------------------------------- the files

    /**
     * A project is a folder and the app finally treats it like one: every file in
     * it is visible in the tree, openable, and kept in step with what the agent
     * writes behind the app's back. These are the states behind that.
     */
    private val history = FileHistory.under(workspace)

    private val _tree = MutableStateFlow<List<FileNode>>(emptyList())
    val tree: StateFlow<List<FileNode>> = _tree.asStateFlow()

    /** Absolute paths of the folders the user opened, so the tree keeps them open. */
    private val _expandedDirs = MutableStateFlow<Set<String>>(emptySet())
    val expandedDirs: StateFlow<Set<String>> = _expandedDirs.asStateFlow()

    private val _treeFilter = MutableStateFlow(TreeFilter())
    val treeFilter: StateFlow<TreeFilter> = _treeFilter.asStateFlow()

    /** The file the bottom bar acts on. */
    private val _inspected = MutableStateFlow<File?>(null)
    val inspected: StateFlow<File?> = _inspected.asStateFlow()

    /** The file open in the editor; null means the mode's entry point (main.py). */
    private val _editorFile = MutableStateFlow<File?>(null)
    val editorFile: StateFlow<File?> = _editorFile.asStateFlow()

    /** Set when the open file changed on disk and the editor still holds the old text. */
    private val _diskConflict = MutableStateFlow<File?>(null)
    val diskConflict: StateFlow<File?> = _diskConflict.asStateFlow()

    private val _preview = MutableStateFlow<FilePreview>(FilePreview.None)
    val preview: StateFlow<FilePreview> = _preview.asStateFlow()

    private val _searchHits = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchHits: StateFlow<List<SearchHit>> = _searchHits.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _historyFor = MutableStateFlow<File?>(null)
    val historyFor: StateFlow<File?> = _historyFor.asStateFlow()

    private val _versions = MutableStateFlow<List<Version>>(emptyList())
    val versions: StateFlow<List<Version>> = _versions.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** "Take me to the editor" - the file manager asks for the tab, not the tab bar. */
    private val _tabRequest = MutableStateFlow<Int?>(null)
    val tabRequest: StateFlow<Int?> = _tabRequest.asStateFlow()

    private val watchExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pocketrun-watch").apply { isDaemon = true } }

    @Volatile
    private var watching = false

    /** Last seen tree fingerprint; a change in it is what triggers a rebuild. */
    @Volatile
    private var treePrint = ""

    /** (size, mtime) of the file the editor currently holds, to notice edits under it. */
    @Volatile
    private var loadedPrint = ""

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

    /** Extracted native libs live here on Android (empty in JVM unit tests). */
    val nativeLibraryDir: File by lazy {
        File(getApplication<Application>().applicationInfo.nativeLibraryDir)
    }

    /** Real Node 18 (nodejs-mobile) when its native launcher is packaged. */
    val nodeRuntime: NodeRuntime by lazy { NodeRuntime(nativeLibraryDir, workspace) }

    /** BusyBox for the long tail of real shell utilities. */
    val busybox: Busybox by lazy { Busybox(nativeLibraryDir, workspace) }

    /** Native Node if available, Rhino otherwise. */
    val activeNode: SyncScriptRuntime by lazy {
        if (nodeRuntime.isAvailable()) nodeRuntime else jsRuntime
    }

    val npxRuntime: NpxRuntime by lazy { NpxRuntime(activeNode, workspace) }

    // ---------------------------------------------------------------- agent

    /** One bubble / row in the agent conversation. */
    sealed interface AgentItem {
        data class User(val text: String) : AgentItem
        data class Assistant(val text: String) : AgentItem
        data class Tool(val name: String, val args: String, val result: String?) : AgentItem
        data class Error(val text: String) : AgentItem
        data class Info(val text: String) : AgentItem
    }

    /** A question the agent is blocked on, waiting for the user's answer. */
    data class PendingQuestion(
        val questions: List<OpenCodeTools.Question>,
        val answer: (String) -> Unit,
    )

    /** A mutating tool call the agent is blocked on in manual confirm mode. */
    data class PendingApproval(
        val tool: String,
        val argsPreview: String,
        /** (approve this call, remember the choice for future calls of the same pattern). */
        val respond: (Boolean, Boolean) -> Unit,
    )

    /**
     * Everything one session needs while its turn runs. Kept apart from the
     * shared ViewModel state so several sessions can work at the same time:
     * each tab owns its own messages, counters, cancellation and pending
     * prompts, and the public flows below just mirror whichever tab is active.
     */
    class SessionRun(val id: String, val projectDir: File?) {
        val messages = MutableStateFlow<List<AgentItem>>(emptyList())
        val todos = MutableStateFlow<List<Sessions.Todo>>(emptyList())
        val running = MutableStateFlow(false)
        val preview = MutableStateFlow("")
        val steps = MutableStateFlow(0)
        val rounds = MutableStateFlow(0)
        val startedAt = MutableStateFlow(0L)
        val pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
        val pendingApproval = MutableStateFlow<PendingApproval?>(null)
        val cancelled = AtomicBoolean(false)

        /** The OpenAI-format history this session's next request is built from. */
        @Volatile
        var history: JSONArray = JSONArray()

        /** Set when the session is deleted mid-turn, so the turn stops saving it. */
        @Volatile
        var deleted: Boolean = false

        /**
         * `/undo` history: each entry is one reversible step (the conversation
         * plus every file a turn changed). Newest is last. `/redo` restores the
         * steps the last undos reverted.
         */
        val undoStack = ArrayDeque<Change>()
        val redoStack = ArrayDeque<Change>()

        /** The step being recorded by the in-flight turn, if any. */
        @Volatile
        var pendingChange: Change? = null

        /** Rough token estimate of [history] (plus tools/prompt overhead). */
        val context = MutableStateFlow(0)

        /** When a subagent writes files, they belong to the parent's step. */
        @Volatile
        var undoTarget: SessionRun? = null

        /** Unblocks whoever is waiting on a question/approval when the turn stops. */
        fun release() {
            pendingQuestion.value?.answer?.invoke("(отменено пользователем)")
            pendingApproval.value?.respond?.invoke(false, false)
            pendingQuestion.value = null
            pendingApproval.value = null
        }
    }

    /** One saved file state: [version] is null when the file must be absent. */
    class FileState(val file: File, val version: Version?)

    /** A reversible step: the conversation and file states to restore. */
    class Change {
        var history: JSONArray = JSONArray()
        var messages: List<AgentItem> = emptyList()
        val files = mutableListOf<FileState>()
        val keys = mutableSetOf<String>()
    }

    private val _llmConfig = MutableStateFlow(AgentSettings.read(app))
    val llmConfig: StateFlow<AgentSettings.Config> = _llmConfig.asStateFlow()

    /**
     * The agent selected with `/agent` for the current project, or null for the
     * built-in one. Custom agents come from opencode.json and `.opencode/agent`.
     */
    private val _activeAgent = MutableStateFlow<String?>(null)
    val activeAgent: StateFlow<String?> = _activeAgent.asStateFlow()

    /** The merged opencode.json + .opencode config for the selected project. */
    fun openCodeConfig(): OpenCodeConfig =
        OpenCodeConfig.load(workspace, _selected.value?.dir)

    /** Sessions: the opencode-style local session store. */
    val sessions = Sessions(workspace)

    private val _sessionList = MutableStateFlow<List<Sessions.SessionInfo>>(emptyList())
    val sessionList: StateFlow<List<Sessions.SessionInfo>> = _sessionList.asStateFlow()

    private val _currentSession = MutableStateFlow<Sessions.SessionInfo?>(null)
    val currentSession: StateFlow<Sessions.SessionInfo?> = _currentSession.asStateFlow()

    /** Live turn state, one entry per session that has been opened. */
    private val _runs = MutableStateFlow<Map<String, SessionRun>>(emptyMap())
    private val _activeRunId = MutableStateFlow<String?>(null)

    /** The run behind the active tab, or null when no tab is open. */
    val activeRun: StateFlow<SessionRun?> =
        combine(_runs, _activeRunId) { runs, id -> id?.let { runs[it] } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> activeFlow(fallback: T, select: (SessionRun) -> StateFlow<T>): StateFlow<T> =
        activeRun.flatMapLatest { run -> if (run == null) flowOf(fallback) else select(run) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, fallback)

    val agentMessages: StateFlow<List<AgentItem>> = activeFlow(emptyList<AgentItem>()) { it.messages }
    val agentRunning: StateFlow<Boolean> = activeFlow(false) { it.running }
    val todos: StateFlow<List<Sessions.Todo>> = activeFlow(emptyList<Sessions.Todo>()) { it.todos }
    val pendingQuestion: StateFlow<PendingQuestion?> = activeFlow(null as PendingQuestion?) { it.pendingQuestion }
    val pendingApproval: StateFlow<PendingApproval?> = activeFlow(null as PendingApproval?) { it.pendingApproval }
    val modelPreview: StateFlow<String> = activeFlow("") { it.preview }
    val agentSteps: StateFlow<Int> = activeFlow(0) { it.steps }
    val agentRounds: StateFlow<Int> = activeFlow(0) { it.rounds }
    val turnStartedAt: StateFlow<Long> = activeFlow(0L) { it.startedAt }
    val agentContextTokens: StateFlow<Int> = activeFlow(0) { it.context }

    /** Ids of every session with a turn in flight, so a tab can show it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val runningSessionIds: StateFlow<Set<String>> =
        _runs.flatMapLatest { runs ->
            if (runs.isEmpty()) flowOf(emptySet())
            else combine(runs.values.map { it.running }) { flags ->
                runs.keys.filterIndexed { i, _ -> flags[i] }.toSet()
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Model catalog loaded from the configured server (GET /models). */
    private val _modelCatalog = MutableStateFlow<List<String>>(emptyList())
    val modelCatalog: StateFlow<List<String>> = _modelCatalog.asStateFlow()

    private val _modelCatalogState = MutableStateFlow<String?>(null)
    val modelCatalogState: StateFlow<String?> = _modelCatalogState.asStateFlow()

    /** Open tabs — each tab is its own agent session (browser-style). */
    private val _openTabs = MutableStateFlow<List<Sessions.SessionInfo>>(emptyList())
    val openTabs: StateFlow<List<Sessions.SessionInfo>> = _openTabs.asStateFlow()

    /** Last user prompt, for the retry button. */
    @Volatile
    private var lastPrompt: String? = null

    /** Several sessions may run at once; each turn takes one thread. */
    private val agentExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_PARALLEL_TURNS) { r ->
        Thread(r, "pocketrun-agent").apply { isDaemon = true }
    }

    private val ioExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pocketrun-io").apply { isDaemon = true } }

    fun canUseAgent(): Boolean =
        (licenseState.value as? LicenseManager.LicenseState.Active)?.canUseAgent == true

    fun saveLlmConfig(config: AgentSettings.Config) {
        AgentSettings.save(getApplication(), config)
        _llmConfig.value = config
    }

    /** GET {baseUrl}/models — the Zen catalog for the Zen preset. */
    fun fetchModelCatalog(baseUrl: String, apiKey: String) {
        _modelCatalogState.value = "загрузка…"
        ioExecutor.execute {
            try {
                val models = LlmClient.models(baseUrl, apiKey)
                _modelCatalog.value = models
                _modelCatalogState.value = if (models.isEmpty()) "сервер не вернул моделей" else null
            } catch (t: Throwable) {
                _modelCatalog.value = emptyList()
                _modelCatalogState.value = t.message ?: t.javaClass.simpleName
            }
        }
    }

    fun clearAgentChat() {
        val run = activeRun.value ?: return
        if (run.running.value) return
        run.messages.value = emptyList()
        run.todos.value = emptyList()
        run.pendingQuestion.value = null
    }

    fun refreshSessions() {
        _sessionList.value = sessions.list()
    }

    private fun projectName(): String? = _selected.value?.name

    /** The directory a session belongs to (its project, mapped back to a folder). */
    private fun projectDirFor(info: Sessions.SessionInfo): File? =
        info.project?.let { name -> _projects.value.firstOrNull { it.name == name }?.dir }

    /** The live run for a session, created on first use. */
    private fun runFor(info: Sessions.SessionInfo): SessionRun {
        _runs.value[info.id]?.let { return it }
        val run = SessionRun(info.id, projectDirFor(info))
        _runs.update { if (it.containsKey(info.id)) it else it + (info.id to run) }
        return _runs.value[info.id] ?: run
    }

    /**
     * Makes a session the active tab. A session that is not running has its
     * history and todos reloaded from disk; a running one keeps the live state
     * it already has (reloading would stomp on the turn in flight).
     */
    private fun activate(info: Sessions.SessionInfo) {
        val run = runFor(info)
        if (!run.running.value) {
            val loaded = sessions.load(info.id)
            if (loaded != null) {
                run.history = loaded.messages
                run.messages.value = historyToItems(loaded.messages)
                run.todos.value = loaded.todos
            }
        }
        _currentSession.value = info
        _activeRunId.value = info.id
    }

    /** Remembers the active tab and open tabs for the next app launch. */
    private fun persistAgentUi() {
        AgentSettings.saveUiState(getApplication(), _currentSession.value?.id, _openTabs.value.map { it.id })
    }

    /** Creates a fresh session for the current project and opens it as a tab. */
    private fun createSession(project: String? = projectName()): Sessions.SessionInfo {
        val session = sessions.create(project = project, title = null, model = _llmConfig.value.model)
        activate(session.info)
        registerTab(session.info)
        refreshSessions()
        persistAgentUi()
        return session.info
    }

    /** `/new` — starts a fresh session for the current project. */
    fun newSession() {
        createSession()
    }

    /** Resumes a stored session: history and todos come back into the chat. */
    fun openSession(id: String) {
        val info = _sessionList.value.firstOrNull { it.id == id }
            ?: sessions.load(id)?.info
            ?: return
        activate(info)
        registerTab(info)
        refreshSessions()
        persistAgentUi()
    }

    fun forkSession(id: String) {
        sessions.fork(id)?.let { child ->
            val run = runFor(child.info)
            run.history = child.messages
            run.messages.value = historyToItems(child.messages)
            run.todos.value = child.todos
            _currentSession.value = child.info
            _activeRunId.value = child.info.id
            registerTab(child.info)
        }
        refreshSessions()
        persistAgentUi()
    }

    fun renameSession(id: String, title: String) {
        sessions.rename(id, title)
        val updated = sessions.list().firstOrNull { it.id == id }
        if (updated != null) {
            _openTabs.value = _openTabs.value.map { if (it.id == id) updated else it }
            if (_currentSession.value?.id == id) _currentSession.value = updated
        }
        refreshSessions()
        persistAgentUi()
    }

    fun deleteSession(id: String) {
        // Stop a turn that is still in flight so it cannot resurrect the file.
        _runs.value[id]?.let { run ->
            run.deleted = true
            run.cancelled.set(true)
            run.release()
        }
        sessions.delete(id)
        _runs.update { it - id }
        _openTabs.value = _openTabs.value.filterNot { it.id == id }
        if (_currentSession.value?.id == id) {
            val next = _openTabs.value.firstOrNull()
            if (next != null) {
                activate(next)
            } else {
                _currentSession.value = null
                _activeRunId.value = null
            }
        }
        refreshSessions()
        persistAgentUi()
    }

    /** `opencode export` analog: writes the session to opencode-data/exports/. */
    fun exportSession(id: String) {
        val path = sessions.export(id)
        pushItem(
            AgentItem.Info(
                if (path != null) "Сессия сохранена: $path"
                else "Не удалось экспортировать сессию",
            ),
        )
    }

    private fun historyToItems(messages: JSONArray): List<AgentItem> {
        val items = mutableListOf<AgentItem>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "user" -> m.optString("content").takeIf { it.isNotBlank() }?.let { items += AgentItem.User(it) }
                "assistant" -> {
                    m.optString("content").takeIf { it.isNotBlank() }?.let { items += AgentItem.Assistant(it) }
                    val calls = m.optJSONArray("tool_calls")
                    if (calls != null) {
                        for (c in 0 until calls.length()) {
                            val fn = calls.optJSONObject(c)?.optJSONObject("function") ?: continue
                            items += AgentItem.Tool(fn.optString("name"), fn.optString("arguments").take(120), "")
                        }
                    }
                }
            }
        }
        return items
    }

    /** Appends a bubble to the active tab's conversation. */
    private fun pushItem(item: AgentItem) {
        activeRun.value?.messages?.update { it + item }
    }

    /** The active session, created on demand so a command always has a tab. */
    private fun ensureSession(): Sessions.SessionInfo =
        _currentSession.value ?: createSession()

    fun sendToAgent(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return

        // `@path` in a prompt pulls that file's contents in as context.
        val (sendText, showText) = expandMentions(prompt)

        // Chat commands, opencode-style. They always act on the active tab.
        when (showText) {
            "/new" -> {
                newSession()
                pushItem(AgentItem.Info("Новая сессия${projectName()?.let { " для проекта $it" } ?: ""}."))
                return
            }
            "/init" -> {
                val info = ensureSession()
                if (!requireConfigured()) return
                runAgentTurn(
                    "Create an AGENTS.md file in the current project root: briefly describe the project " +
                        "(look at the files first) and add short, useful rules for future agents working in it. " +
                        "Keep it under 30 lines. Reply with a one-line summary.",
                    info,
                    display = "/init",
                )
                return
            }
            "/continue" -> {
                val info = ensureSession()
                if (!requireConfigured()) return
                runAgentTurn(OpenCodeAgent.CONTINUE_PROMPT, info, display = "/continue")
                return
            }
            "/skills" -> {
                ensureSession()
                val found = dev.pocketrun.agent.opencode.Skills(workspace).discover(_selected.value?.dir)
                pushItem(
                    AgentItem.Info(
                        if (found.isEmpty()) "Навыков не найдено. Создайте свой: папка .opencode/skills/<имя>/SKILL.md в проекте с frontmatter name и description (или попросите агента — он умеет создавать навыки сам)."
                        else "Навыки:\n" + found.joinToString("\n") { s -> "• ${s.name}${if (s.global) " (глобальный)" else ""} — ${s.description}" },
                    ),
                )
                return
            }
            "/help" -> {
                ensureSession()
                val custom = openCodeConfig().commandNames()
                pushItem(
                    AgentItem.Info(
                        "Команды: /new — новая сессия · /init — создать AGENTS.md · /continue — продолжить прерванный ход · /skills — список навыков · " +
                            "/agent <имя> — выбрать агента · /agents — список агентов · /models — модели · /sessions — сессии · /editor — открыть редактор · " +
                            "/compact — сжать историю · /undo — отменить изменения последнего хода · /redo — вернуть отменённое · /share — поделиться сессией · /find — поиск по сессиям · /import — импорт сессии · /help — эта справка" +
                            (if (custom.isNotEmpty()) " · свои команды: " + custom.joinToString(", ") { "/$it" } else "") + ".\n\n" +
                            "Свои команды: opencode.json → command, или файл .opencode/command/<имя>.md (frontmatter description/agent/model + шаблон с \$ARGUMENTS, \$1…\$9 и @файл).\n" +
                            "Агенты: opencode.json → agent, или .opencode/agent/<имя>.md (frontmatter description/model/tools/mode + промпт в теле).\n" +
                            "Права: opencode.json → permission (allow/ask/deny, для bash — с шаблонами вроде \"rm *\": \"deny\").\n\n" +
                            "Отключить инструменты глобально: opencode.json → tools (например {\"websearch\": false}).\n" +
                            "Свои провайдеры: opencode.json → provider (baseURL, apiKey, models) — модель вида provider/model возьмёт их. Каталог моделей: /models dev.\n" +
                            "Форматтеры: opencode.json → formatter ({\"prettier\": {\"command\": [\"npx\",\"prettier\",\"--write\",\"\$FILE\"]}}) — запускаются после записи файла. Prettier также подхватывается автоматически по конфигу.\n" +
                            "@файл в сообщении подкладывает его содержимое агенту.\n" +
                            "Новое задание, пока агент занят, открывает отдельную сессию и выполняется параллельно — вкладки сверху.\n" +
                            "Навыки: создайте .opencode/skills/<имя>/SKILL.md (frontmatter: name, description) — агент подхватит и сможет загружать; глобальные — workspace/opencode/skills/.\n" +
                            "Инструкции: AGENTS.md в проекте + глобальный opencode/AGENTS.md, фолбэк CLAUDE.md; список файлов/URL — в opencode/opencode.json (\"instructions\").\n" +
                            "GitHub: ⚙ → GitHub (PAT с правами repo, workflow) — агент сможет создавать репозитории, Actions-раннеры (ubuntu-latest, там работают npm и opencode), запускать сборку и ждать её через github run_wait (одним вызовом, с логами при падении), забирать артефакты.\n" +
                            "Подтверждения и лимит раундов: ⚙ → «Подтверждения» и «Раундов на ход». Долгие сборки и ожидание — это нормально: раундов по умолчанию 40.\n" +
                            "Файл агенту: кнопка 📎 — файл копируется в проект и отправляется агенту.\n" +
                            "Остановка: ⏹ — ход прерывается, но сессия и история сохраняются (/continue продолжает).",
                    ),
                )
                return
            }
        }

        // Commands that take arguments, plus custom commands from the config.
        val parsed = Commands.parse(showText)
        if (parsed != null) {
            val (name, args) = parsed
            when (name) {
                "undo" -> {
                    ensureSession()
                    undoLastTurn()
                    return
                }
                "redo" -> {
                    ensureSession()
                    redoLastTurn()
                    return
                }
                "share" -> {
                    ensureSession()
                    val id = args.trim().takeIf { it.isNotEmpty() } ?: _currentSession.value?.id
                    if (id == null) {
                        pushItem(AgentItem.Info("Нет активной сессии."))
                        return
                    }
                    val path = sessions.export(id)
                    if (path == null) {
                        pushItem(AgentItem.Error("Не удалось экспортировать сессию."))
                    } else {
                        pushItem(AgentItem.Info("Сессия экспортирована: $path"))
                        shareFile(File(path))
                    }
                    return
                }
                "find" -> {
                    ensureSession()
                    val q = args.trim()
                    if (q.isEmpty()) {
                        pushItem(AgentItem.Info("Укажите текст: /find <текст>"))
                        return
                    }
                    val hits = sessions.search(q)
                    if (hits.isEmpty()) {
                        pushItem(AgentItem.Info("Ничего не найдено по «$q»."))
                    } else {
                        val text = buildString {
                            appendLine("Найдено по «$q»:")
                            hits.take(20).forEach { h ->
                                val snip = sessions.snippet(sessions.load(h.info.id)?.messages ?: org.json.JSONArray(), q, 80)
                                appendLine("• ${h.info.title} (${h.count})${if (snip.isNotBlank()) "\n  $snip" else ""}")
                            }
                        }
                        pushItem(AgentItem.Info(text.trimEnd()))
                    }
                    return
                }
                "import" -> {
                    ensureSession()
                    val arg = args.trim()
                    if (arg.isEmpty()) {
                        pushItem(AgentItem.Info("Укажите файл экспорта: /import <путь>. Файлы — workspace/opencode-data/exports/."))
                        return
                    }
                    val base = _selected.value?.dir ?: workspace.root
                    val f = File(arg).takeIf { it.isAbsolute } ?: workspace.resolve(arg, base) ?: File(base, arg)
                    val session = sessions.import(f)
                    if (session == null) {
                        pushItem(AgentItem.Error("Не удалось импортировать сессию из ${f.path}."))
                    } else {
                        refreshSessions()
                        switchTab(session.info.id)
                        pushItem(AgentItem.Info("Сессия импортирована: ${session.info.title}"))
                    }
                    return
                }
                "compact" -> {
                    ensureSession()
                    compactSession()
                    return
                }
                "editor" -> {
                    _tabRequest.value = TAB_EDITOR
                    return
                }
                "models" -> {
                    ensureSession()
                    val cfg = openCodeConfig()
                    if (args.trim() == "dev") {
                        pushItem(AgentItem.Info("Загружаю каталог models.dev…"))
                        ioExecutor.execute {
                            val catalog = dev.pocketrun.agent.opencode.ModelsDev.load(File(workspace.root, "opencode/cache"))
                            val text = if (catalog.isEmpty()) {
                                "Не удалось загрузить models.dev (нет сети?)."
                            } else {
                                buildString {
                                    appendLine("models.dev: ${catalog.size} провайдеров")
                                    catalog.values.take(40).forEach { p ->
                                        appendLine("• ${p.id} (${p.name}) — ${p.models.size} моделей")
                                    }
                                    if (catalog.size > 40) appendLine("…")
                                    append("Пример: ${catalog.values.firstOrNull()?.models?.take(8)?.joinToString { it.id } ?: "—"}")
                                }
                            }
                            pushItem(AgentItem.Info(text))
                        }
                        return
                    }
                    val current = _llmConfig.value.model
                    val listed = _modelCatalog.value
                    val text = buildString {
                        appendLine("Текущая: $current")
                        if (cfg.providers.isNotEmpty()) {
                            appendLine("Провайдеры из opencode.json:")
                            cfg.providers.values.forEach { p ->
                                appendLine("• ${p.id}${p.baseUrl?.let { " → $it" } ?: ""}${if (p.models.isNotEmpty()) " (${p.models.joinToString(", ")})" else ""}")
                            }
                        }
                        if (listed.isNotEmpty()) {
                            appendLine("Доступные на сервере:")
                            listed.take(50).forEach { appendLine("• $it") }
                        } else {
                            appendLine("Пресеты:")
                            AgentSettings.PRESETS.forEach { appendLine("• ${it.label} — ${it.model}") }
                        }
                    }
                    pushItem(AgentItem.Info("$text\nСменить: кнопка ⚙ над чатом. Каталог models.dev: /models dev."))
                    return
                }
                "agents" -> {
                    ensureSession()
                    val cfg = openCodeConfig()
                    val custom = cfg.agentNames()
                    val text = buildString {
                        appendLine("Агенты: build (по умолчанию), plan, explore, general")
                        if (custom.isNotEmpty()) {
                            appendLine("Пользовательские:")
                            custom.forEach { n ->
                                val d = cfg.agent(n)
                                appendLine("• $n${d?.mode?.let { " [$it]" } ?: ""}${d?.description?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}")
                            }
                        }
                        appendLine("Текущий: ${_activeAgent.value ?: "build"}")
                        append("Выбрать: /agent <имя>")
                    }
                    pushItem(AgentItem.Info(text))
                    return
                }
                "agent" -> {
                    ensureSession()
                    if (args.isBlank()) {
                        pushItem(AgentItem.Info("Укажите имя: /agent <имя>. Список — /agents."))
                        return
                    }
                    val cfg = openCodeConfig()
                    val target = args.trim()
                    if (target == "build" || target == "plan" || cfg.agent(target) != null) {
                        _activeAgent.value = if (target == "build") null else target
                        pushItem(AgentItem.Info("Агент: $target"))
                    } else {
                        pushItem(AgentItem.Error("Неизвестный агент «$target». Список — /agents."))
                    }
                    return
                }
                "sessions" -> {
                    ensureSession()
                    val list = _sessionList.value
                    val body = if (list.isEmpty()) "Сессий пока нет."
                    else list.take(20).joinToString("\n") { s ->
                        val mark = if (s.id == _currentSession.value?.id) "● " else "• "
                        "$mark${s.title}${s.model?.let { " — $it" } ?: ""}"
                    }
                    pushItem(AgentItem.Info("Сессии:\n$body\nОткрыть: вкладка или список сессий (📂 в шапке агента)."))
                    return
                }
            }

            // A custom command from the config expands and runs as a normal turn.
            val cfg = openCodeConfig()
            val custom = cfg.command(name)
            if (custom != null) {
                val info = ensureSession()
                if (!requireConfigured()) return
                if (custom.agent != null && (custom.agent == "build" || custom.agent == "plan" || cfg.agent(custom.agent) != null)) {
                    _activeAgent.value = if (custom.agent == "build") null else custom.agent
                }
                val expanded = Commands.expand(custom, args, _selected.value?.dir)
                lastPrompt = expanded
                val display = "/$name${if (args.isBlank()) "" else " $args"}"
                runAgentTurn(expanded, info, display = display)
                return
            }
        }

        val active = activeRun.value
        if (active != null && active.running.value) {
            // The active tab is busy, so this is a new task: give it its own
            // session and run it at the same time as the current turn.
            val info = ensureSession()
            val agentInfo = if (info.id == active.id) createSession() else info
            if (!requireConfigured()) return
            lastPrompt = showText
            runAgentTurn(sendText, agentInfo, display = showText)
        } else {
            val info = ensureSession()
            if (!requireConfigured()) return
            lastPrompt = showText
            runAgentTurn(sendText, info, display = showText)
        }
    }

    /**
     * Expands `@path` mentions in a prompt: each matching project file's
     * contents is appended so the agent sees it, while the chat still shows the
     * short prompt the user typed.
     */
    private fun expandMentions(prompt: String): Pair<String, String> {
        if (prompt.startsWith("/")) return prompt to prompt
        val projectDir = _selected.value?.dir ?: return prompt to prompt
        val regex = Regex("@([A-Za-z0-9_./\\-]+)")
        val notes = mutableListOf<String>()
        for (m in regex.findAll(prompt)) {
            val path = m.groupValues[1]
            val f = listOf(File(projectDir, path), workspace.resolve(path, projectDir) ?: File(projectDir, path))
                .firstOrNull { it.isFile && workspace.isInside(it) }
                ?: continue
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()?.take(20_000) ?: continue
            notes += "Файл ${workspace.relativeTo(f)}:\n$text"
        }
        if (notes.isEmpty()) return prompt to prompt
        return (prompt + "\n\n" + notes.joinToString("\n\n")) to prompt
    }

    /** Re-runs the last prompt (shown next to errors). */
    fun retryLast() {
        val p = lastPrompt ?: return
        if (p.isEmpty()) return
        activeRun.value?.let { if (it.running.value) return }
        val info = ensureSession()
        if (!requireConfigured()) return
        runAgentTurn(p, info, display = "$p (повтор)")
    }

    /**
     * Picks a stopped turn back up: the history, todos and every file the
     * agent already touched are in place, so "продолжай" is all it needs.
     */
    fun continueLast() {
        activeRun.value?.let { if (it.running.value) return }
        val info = ensureSession()
        if (!requireConfigured()) return
        runAgentTurn(OpenCodeAgent.CONTINUE_PROMPT, info, display = "/continue")
    }

    private fun requireConfigured(): Boolean {
        if (!canUseAgent()) {
            pushItem(AgentItem.Error("Агент доступен на плане Pro — активируйте Pro-лицензию."))
            return false
        }
        val config = _llmConfig.value
        if (!config.isReady) {
            pushItem(AgentItem.Info("Сначала настройте модель: базовый URL и имя модели (кнопка ⚙ над чатом). API-ключ не нужен для локальных серверов; для Zen — бесплатный ключ с opencode.ai/auth."))
            return false
        }
        return true
    }

    /**
     * Starts a turn for [info]'s session. Different sessions run in parallel
     * (one thread each); a session that is already running refuses a second
     * turn so its history cannot be written from two places at once.
     */
    private fun runAgentTurn(userText: String, info: Sessions.SessionInfo, display: String? = null) {
        val config = _llmConfig.value
        if (!config.isReady) return
        val run = runFor(info)
        if (!run.running.compareAndSet(false, true)) return
        run.cancelled.set(false)
        run.steps.value = 0
        run.rounds.value = 0
        run.preview.value = ""
        run.startedAt.value = System.currentTimeMillis()
        // Capture the boundary for /undo before the turn touches anything.
        val change = Change().apply {
            history = run.history
            messages = run.messages.value
        }
        run.pendingChange = change
        run.messages.update { it + AgentItem.User(display ?: userText) }
        persistAgentUi()

        agentExecutor.execute {
            try {
                performTurn(run, info, userText, config, subagent = null, allowedTools = null)
            } catch (t: Throwable) {
                // Anything unexpected must not leave the chat stuck on "working".
                run.messages.update { it + AgentItem.Error(t.message ?: t.javaClass.simpleName) }
            } finally {
                run.preview.value = ""
                run.pendingQuestion.value = null
                run.pendingApproval.value = null
                run.running.value = false
                commitChange(run)
                // Top-level turns only: a subagent's result is returned inline.
                dev.pocketrun.agent.TurnNotifier.notifyTurnFinished(getApplication(), info.title, "Агент завершил ход.")
                // The agent writes files itself; the tree must not wait for the
                // next poll to show what it did.
                agentTouchedFiles()
            }
        }
    }

    /** Pushes the finished turn onto the undo stack (dropping any redo tail). */
    private fun commitChange(run: SessionRun) {
        val change = run.pendingChange ?: return
        run.pendingChange = null
        // A turn that neither changed files nor the conversation is not a step.
        if (change.files.isEmpty() && run.history.length() == change.history.length()) return
        synchronized(run) {
            run.undoStack.addLast(change)
            if (run.undoStack.size > MAX_UNDO) run.undoStack.removeFirst()
            run.redoStack.clear()
        }
    }

    /** Writes an undone/redone conversation back to the session file. */
    private fun persistRunConversation(run: SessionRun) {
        val session = sessions.load(run.id) ?: return
        session.messages = run.history
        sessions.save(session)
        refreshSessions()
        persistAgentUi()
    }

    /**
     * One turn, synchronous on the calling thread so a `task` subagent can run
     * it inline. Assembles tools for the session, streams events into the run's
     * UI state and persists the result even after a cancellation.
     */
    private fun performTurn(
        run: SessionRun,
        info: Sessions.SessionInfo,
        userText: String,
        config: AgentSettings.Config,
        subagent: OpenCodeTools.TaskRequest?,
        allowedTools: Set<String>?,
    ) {
        val start = System.currentTimeMillis()
        val projectDir = run.projectDir ?: _selected.value?.dir
        val cfg = openCodeConfig()
        // A custom agent pins its model/tools: the one from /agent for the top
        // level, or the requested type for a `task` subagent.
        val agentDef = if (subagent != null) cfg.agent(subagent.subagentType)
        else _activeAgent.value?.let { cfg.agent(it) }
        // A model pinned by an agent (or written as provider/model) may select a
        // configured provider's own endpoint and key.
        val effectiveModel = agentDef?.model ?: config.model
        val provider = cfg.providerFor(effectiveModel)
        val providerKey = provider?.apiKey?.takeIf { it.isNotBlank() }
            ?: OpenCodeConfig.readAuthTokens(workspace.root)[provider?.id ?: effectiveModel.substringBefore('/', "")]
        val effective = config.copy(
            model = effectiveModel,
            baseUrl = provider?.baseUrl ?: config.baseUrl,
            apiKey = providerKey ?: config.apiKey,
            temperature = agentDef?.temperature ?: config.temperature,
            topP = agentDef?.topP ?: config.topP,
            maxOutputTokens = agentDef?.maxOutputTokens ?: config.maxOutputTokens,
            reasoningEffort = agentDef?.reasoningEffort ?: config.reasoningEffort,
        )
        val disabled = cfg.disabledTools()
        val baseTools = agentDef?.tools ?: allowedTools
        val enabledTools = (baseTools ?: OpenCodeTools.ALL_TOOLS).minus(disabled)
        // A subagent never gets `task` (no nesting) or `question` (no user).
        val effectiveTools = if (subagent != null) enabledTools.minus(setOf("task", "question")) else enabledTools
        val tools = OpenCodeTools(workspace, activeNode, npxRuntime, projectDir, effective.githubToken, busybox)
        tools.allowedTools = effectiveTools

        // Permission gate built from opencode.json; the mode is only a fallback.
        tools.permissionResolver = { name, argsJson ->
            val args = org.json.JSONObject(argsJson.ifBlank { "{}" })
            val keyArg = OpenCodeConfig.keyArgFor(name, args)
            cfg.permissionFor(name, keyArg)
                ?: if (isAlwaysAllowed(name, keyArg)) OpenCodeConfig.Permission.ALLOW
                else if (tools.isReadOnly(name, args) || subagent != null) OpenCodeConfig.Permission.ALLOW
                else if (config.confirmMode == AgentSettings.MODE_MANUAL) OpenCodeConfig.Permission.ASK
                else OpenCodeConfig.Permission.ALLOW
        }
        // Snapshot each file before the agent changes it, for /undo.
        tools.onBeforeWrite = { file -> recordTurnFile(run, file, projectDir) }
        tools.formatters = cfg.formatters + cfg.autoFormatters(projectDir, File(workspace.root, "opencode"))
        if (subagent == null) {
            // Only top-level agents spawn subagents; nesting stays impossible.
            tools.subagentRunner = { request -> runSubagent(info, request, config, run) }
        }
        val agent = OpenCodeAgent(
            workspace, tools, { LlmClient(effective) }, projectDir,
            maxSteps = config.safeMaxSteps,
            askMode = config.confirmMode == AgentSettings.MODE_ASK && subagent == null,
            hasGitHub = config.hasGitHub,
            isCancelled = { run.cancelled.get() },
            subagentType = subagent?.subagentType,
            agentName = agentDef?.name,
            agentPrompt = agentDef?.prompt,
        )

        // A subagent cannot ask the user, so it gets no interactive hooks.
        if (subagent == null) {
            tools.questionAsker = { questions -> askQuestions(run, questions) }
            // The approver serves both manual mode and opencode.json "ask" rules.
            tools.toolApprover = { name, argsJson -> askApproval(run, name, argsJson) }
        }

        tools.onTodos = { list -> run.todos.value = list }
        tools.todos = run.todos.value
        // A waiting tool (github run_wait) must notice ⏹ as well.
        tools.cancelCheck = { run.cancelled.get() }

        if (subagent == null) {
            // Keep the context in view, and squeeze it before it overflows.
            run.context.value = estimatedTokens(run.history)
            if (run.history.length() > 1 && run.context.value >= compactLimitTokens) {
                autoCompact(run, info, effective)
            }
        }

        val newHistory = agent.run(userText, run.history) { event -> handleEvent(run, config, event) }

        // Persist the turn: history, todos, auto-title from the first message.
        run.history = newHistory
        if (run.deleted) return
        val session = sessions.load(info.id)
            ?: sessions.create(project = info.project, title = info.title, model = effective.model)
        session.messages = newHistory
        session.todos = run.todos.value
        session.info.model = effective.model
        if (session.info.title == "Новая сессия") {
            val firstUser = firstUserText(newHistory) ?: userText
            val auto = firstUser.replace('\n', ' ').trim().take(40).let { if (it.length == 40) it + "…" else it }
            if (auto.isNotBlank()) session.info.title = auto
        }
        sessions.save(session)
        // Only mirror into the tab if that tab is still showing this session.
        if (_currentSession.value?.id == info.id) _currentSession.value = session.info
        refreshSessions()
        persistAgentUi()
        if (subagent == null) {
            val secs = (System.currentTimeMillis() - start) / 1000
            if (run.rounds.value > 0 || run.cancelled.get()) {
                run.messages.update {
                    it + AgentItem.Info(
                        "⏱ раундов: ${run.rounds.value} · инструментов: ${run.steps.value} · " +
                            "время: ${secs}с · сессия сохранена",
                    )
                }
            }
        }
    }

    private fun handleEvent(run: SessionRun, config: AgentSettings.Config, event: OpenCodeAgent.Event) {
        when (event) {
            is OpenCodeAgent.Event.Round -> run.rounds.value = event.step
            is OpenCodeAgent.Event.Thinking -> run.preview.value = event.preview
            is OpenCodeAgent.Event.Note -> run.messages.update { it + AgentItem.Info(event.text) }
            is OpenCodeAgent.Event.AssistantText -> run.messages.update { it + AgentItem.Assistant(event.text) }
            is OpenCodeAgent.Event.ToolStart -> {
                run.steps.value = run.steps.value + 1
                run.messages.update { it + AgentItem.Tool(event.name, event.args, null) }
            }
            is OpenCodeAgent.Event.ToolDone -> run.messages.update { items ->
                val idx = items.indexOfLast { it is AgentItem.Tool && it.result == null && it.name == event.name }
                if (idx >= 0) items.toMutableList().also { list -> list[idx] = (list[idx] as AgentItem.Tool).copy(result = event.result) }
                else items + AgentItem.Tool(event.name, "", event.result)
            }
            is OpenCodeAgent.Event.Failed -> {
                run.messages.update { it + AgentItem.Error(event.message) }
                // Zen refuses keyless requests with 403 even for free models —
                // tell the user where the free key lives.
                if (config.isZen && "403" in event.message) {
                    run.messages.update {
                        it + AgentItem.Info(
                            "Zen требует ключ даже для бесплатных моделей — получите его бесплатно на opencode.ai/auth " +
                                "(кнопка ⚙ над чатом). Бесплатные модели не расходуют кредиты.",
                        )
                    }
                }
            }
        }
    }

    /** Blocks the turn on the user's answer; the answer arrives from the UI. */
    private fun askQuestions(run: SessionRun, questions: List<OpenCodeTools.Question>): String {
        val latch = CountDownLatch(1)
        var answer: String? = null
        run.pendingQuestion.value = PendingQuestion(questions) { text ->
            answer = text
            run.pendingQuestion.value = null
            latch.countDown()
        }
        return if (latch.await(10, TimeUnit.MINUTES)) {
            answer ?: "(нет ответа)"
        } else {
            run.pendingQuestion.value = null
            "(ответа не последовало)"
        }
    }

    /** Blocks a mutating call on the user's approval in manual mode. */
    private fun askApproval(run: SessionRun, name: String, argsJson: String): Boolean {
        val latch = CountDownLatch(1)
        var approved = false
        val keyArg = OpenCodeConfig.keyArgFor(name, org.json.JSONObject(argsJson.ifBlank { "{}" }))
        run.pendingApproval.value = PendingApproval(name, argsJson.take(300)) { allow, always ->
            approved = allow
            if (allow && always) AgentSettings.rememberApproval(getApplication(), "$name::$keyArg")
            run.pendingApproval.value = null
            latch.countDown()
        }
        return if (latch.await(10, TimeUnit.MINUTES)) {
            approved
        } else {
            run.pendingApproval.value = null
            false
        }
    }

    /** True when the user chose "always allow" for this tool/argument pattern. */
        /** Self-facing extension: needed for always-allow strings. */
    private fun isAlwaysAllowed(name: String, keyArg: String): Boolean {
        val tool = name.trim()
        return AgentSettings.approvedRules(getApplication()).any { rule ->
            val (rtool, rpattern) = rule.split("::", limit = 2).let { it[0] to it.getOrElse(1) { "*" } }
            rtool == tool && OpenCodeConfig.globMatches(rpattern, keyArg)
        }
    }

    /**
     * Runs a `task` subagent: a child session (parentID set) with a restricted
     * toolset, executed synchronously on the parent's thread. Returns its final
     * text so the parent can use it as the tool result.
     */
    private fun runSubagent(
        parent: Sessions.SessionInfo,
        request: OpenCodeTools.TaskRequest,
        config: AgentSettings.Config,
        parentRun: SessionRun,
    ): String {
        val child = sessions.create(
            project = parent.project,
            title = request.description.take(60),
            parentID = parent.id,
            model = config.model,
        )
        val childRun = SessionRun(child.info.id, projectDirFor(child.info))
        // A subagent's file writes belong to the parent turn's undo step.
        childRun.undoTarget = parentRun
        _runs.update { it + (child.info.id to childRun) }
        childRun.history = child.messages
        childRun.messages.value = historyToItems(child.messages)
        childRun.todos.value = child.todos
        childRun.running.value = true
        return try {
            performTurn(
                run = childRun,
                info = child.info,
                userText = request.prompt,
                config = config,
                subagent = request,
                allowedTools = OpenCodeTools.subagentTools(request.subagentType),
            )
            lastAssistantText(childRun.history)
        } catch (t: Throwable) {
            "error субагента: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            childRun.running.value = false
            refreshSessions()
        }
    }

    /** The subagent's answer: its last non-empty assistant message. */
    private fun lastAssistantText(history: JSONArray): String {
        for (i in history.length() - 1 downTo 0) {
            val m = history.optJSONObject(i) ?: continue
            if (m.optString("role") == "assistant") {
                val c = m.optString("content")
                if (c.isNotBlank() && c != "null") return c
            }
        }
        return "(субагент завершил работу без текстового ответа)"
    }

    // ---------------------------------------------------------------- undo & compact

    /**
     * Remembers a file's state before the agent changes it, so `/undo` can put
     * it back. Only the first touch of a turn is recorded: that is the state
     * the turn started from. A subagent's writes land in the parent's step.
     */
    private fun recordTurnFile(run: SessionRun, file: File, projectDir: File?) {
        val dir = projectDir ?: return
        val target = run.undoTarget ?: run
        val change = target.pendingChange ?: return
        val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        if (!change.keys.add(key)) return
        val version = if (file.isFile) history.snapshot(file, dir) else null
        change.files += FileState(file, version)
    }

    /**
     * `/undo`: reverts the last step — its files and its conversation — and
     * makes it available to `/redo`. Repeatable for as many steps as ran.
     */
    fun undoLastTurn() {
        val run = activeRun.value ?: return
        if (run.running.value) {
            pushItem(AgentItem.Info("Агент ещё работает — сначала остановите ход (⏹)."))
            return
        }
        val change = synchronized(run) { run.undoStack.removeLastOrNull() }
        if (change == null) {
            pushItem(AgentItem.Info("Нечего отменять."))
            return
        }
        val projectDir = run.projectDir ?: _selected.value?.dir
        if (projectDir == null) return
        val (names, inverse) = applyChange(run, change, projectDir)
        persistRunConversation(run)
        synchronized(run) {
            run.redoStack.addLast(inverse)
            if (run.redoStack.size > MAX_UNDO) run.redoStack.removeFirst()
        }
        agentTouchedFiles()
        pushItem(
            AgentItem.Info(
                if (names.isEmpty()) "Шаг отменён."
                else "Отменено:\n" + names.joinToString("\n") { "• $it" } + "\nВернуть: /redo",
            ),
        )
    }

    /** `/redo`: re-applies the step the last `/undo` reverted. */
    fun redoLastTurn() {
        val run = activeRun.value ?: return
        if (run.running.value) {
            pushItem(AgentItem.Info("Агент ещё работает — сначала остановите ход (⏹)."))
            return
        }
        val change = synchronized(run) { run.redoStack.removeLastOrNull() }
        if (change == null) {
            pushItem(AgentItem.Info("Нечего возвращать: сначала отмените изменения через /undo."))
            return
        }
        val projectDir = run.projectDir ?: _selected.value?.dir
        if (projectDir == null) return
        val (names, inverse) = applyChange(run, change, projectDir)
        persistRunConversation(run)
        synchronized(run) {
            run.undoStack.addLast(inverse)
            if (run.undoStack.size > MAX_UNDO) run.undoStack.removeFirst()
        }
        agentTouchedFiles()
        pushItem(
            AgentItem.Info(
                if (names.isEmpty()) "Нечего возвращать."
                else "Возвращено:\n" + names.joinToString("\n") { "• $it" },
            ),
        )
    }

    /**
     * Moves the session to [change]'s state (files + conversation) and returns
     * the touched names plus the inverse change that would move it back.
     */
    private fun applyChange(run: SessionRun, change: Change, projectDir: File): Pair<List<String>, Change> {
        val names = mutableListOf<String>()
        val inverse = Change().apply {
            history = run.history
            messages = run.messages.value
        }
        for (fs in change.files) {
            // Remember the current state so the inverse can restore it.
            if (fs.file.isFile) history.snapshot(fs.file, projectDir)?.let { inverse.files += FileState(fs.file, it) }
            else inverse.files += FileState(fs.file, null)
            if (fs.version != null) {
                if (history.restore(fs.version, fs.file, projectDir)) names += workspace.relativeTo(fs.file)
            } else if (fs.file.exists() && fs.file.deleteRecursively()) {
                names += "-${workspace.relativeTo(fs.file)}"
            }
        }
        run.history = change.history
        run.messages.value = change.messages
        return names.distinct() to inverse
    }

    /**
     * `/compact`: replaces the session history with a model-written handoff
     * summary, so a long chat can continue without overflowing the context.
     */
    private fun compactSession() {
        val run = activeRun.value ?: return
        if (run.running.value) {
            pushItem(AgentItem.Info("Агент ещё работает — сожмите историю после завершения хода."))
            return
        }
        val config = _llmConfig.value
        if (!config.isReady) {
            pushItem(AgentItem.Info("Сначала настройте модель (⚙)."))
            return
        }
        val info = ensureSession()
        val transcript = historyText(run.history)
        if (transcript.isBlank()) {
            pushItem(AgentItem.Info("История пуста — нечего сжимать."))
            return
        }
        pushItem(AgentItem.Info("Сжимаю историю…"))
        agentExecutor.execute {
            try {
                val request = JSONArray()
                    .put(
                        JSONObject().put("role", "system").put(
                            "content",
                            "You compress a coding-agent conversation into a dense handoff summary. " +
                                "Keep: the user's goal, decisions made, files touched and their current state, " +
                                "commands run and their results, open questions and the exact next steps. " +
                                "No pleasantries, no repetition — just the facts another agent needs to continue.",
                        ),
                    )
                    .put(JSONObject().put("role", "user").put("content", transcript))
                val summary = LlmClient(config).chat(request, null).content.orEmpty().trim()
                if (summary.isBlank()) {
                    run.messages.update { it + AgentItem.Error("Не удалось получить конспект (пустой ответ модели).") }
                    return@execute
                }
                val compacted = JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("content", "Конспект предыдущей работы (история сжата через /compact):\n" + summary),
                )
                run.history = compacted
                val session = sessions.load(info.id)
                if (session != null) {
                    session.messages = compacted
                    sessions.save(session)
                }
                run.messages.update { it + AgentItem.Info("История сжата. Конспект:\n" + summary.take(1500)) }
            } catch (t: Throwable) {
                run.messages.update { it + AgentItem.Error("Сжатие не удалось: ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    /** Flattens a history into readable text, for summarization. */
    private fun historyText(history: JSONArray): String = buildString {
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "user", "assistant" -> {
                    val c = m.optString("content")
                    if (c.isNotBlank() && c != "null") appendLine("${m.optString("role")}: $c")
                }
                "tool" -> appendLine("tool: ${m.optString("content").take(500)}")
            }
        }
    }.trim()

    /** Rough token estimate of a history: its characters, plus prompt/tool overhead. */
    private fun estimatedTokens(history: JSONArray): Int =
        dev.pocketrun.agent.opencode.HistoryTrimmer.estimateTokens(history) + CONTEXT_OVERHEAD_TOKENS

    private val compactLimitTokens: Int get() = (CONTEXT_WINDOW_TOKENS * AUTO_COMPACT_RATIO).toInt()

    /**
     * `/compact`, but automatic: called on the agent thread when a turn would
     * blow the context window. Rewrites the history to a summary and keeps the
     * undo boundary pointing at it, so undo still works after compaction.
     */
    private fun autoCompact(run: SessionRun, info: Sessions.SessionInfo, effective: AgentSettings.Config): Boolean {
        val transcript = historyText(run.history)
        if (transcript.isBlank()) return false
        val summary = runCatching {
            val request = JSONArray()
                .put(
                    JSONObject().put("role", "system").put(
                        "content",
                        "You compress a coding-agent conversation into a dense handoff summary. " +
                            "Keep: the user's goal, decisions made, files touched and their current state, " +
                            "commands run and their results, open questions and the exact next steps. " +
                            "No pleasantries, no repetition — just the facts another agent needs to continue.",
                    ),
                )
                .put(JSONObject().put("role", "user").put("content", transcript))
            LlmClient(effective).chat(request, null).content.orEmpty().trim()
        }.getOrDefault("")
        if (summary.isBlank()) return false
        val compacted = JSONArray().put(
            JSONObject().put("role", "user")
                .put("content", "Конспект предыдущей работы (контекст сжат автоматически):\n" + summary),
        )
        run.history = compacted
        run.pendingChange?.history = compacted
        run.pendingChange?.messages = run.messages.value
        run.context.value = estimatedTokens(compacted)
        val session = sessions.load(info.id)
        if (session != null) {
            session.messages = compacted
            sessions.save(session)
            if (_currentSession.value?.id == info.id) _currentSession.value = session.info
        }
        run.messages.update { it + AgentItem.Info("Контекст сжат автоматически — достигнут лимит окна.") }
        return true
    }

    // ---------------------------------------------------------------- tabs

    private fun registerTab(info: Sessions.SessionInfo) {
        _openTabs.update { tabs -> if (tabs.any { it.id == info.id }) tabs.map { if (it.id == info.id) info else it } else tabs + info }
    }

    /** Opens (or switches to) a session tab. */
    fun switchTab(id: String) {
        val info = _sessionList.value.firstOrNull { it.id == id }
            ?: _openTabs.value.firstOrNull { it.id == id }
            ?: sessions.load(id)?.info
            ?: return
        activate(info)
        registerTab(info)
        refreshSessions()
        persistAgentUi()
    }

    /** A fresh tab with its own new session. */
    fun newTab() {
        createSession()
    }

    /** Closes the active tab and shows the neighbour (or an empty chat). */
    fun closeCurrentTab() {
        val current = _currentSession.value ?: return
        val tabs = _openTabs.value
        val idx = tabs.indexOfFirst { it.id == current.id }
        _openTabs.value = tabs.filterNot { it.id == current.id }
        val next = tabs.getOrNull(idx + 1) ?: tabs.getOrNull(idx - 1)
        if (next != null) {
            activate(next)
        } else {
            _currentSession.value = null
            _activeRunId.value = null
        }
        persistAgentUi()
    }

    // ---------------------------------------------------------------- files

    /**
     * Sends a file to the agent: copies it into the project's attachments/
     * folder and starts a turn so the agent can work with it (read/bash).
     */
    fun attachFile(uri: android.net.Uri) {
        val resolver = getApplication<Application>().contentResolver
        val name = runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: "file-${System.currentTimeMillis()}.bin"
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").ifEmpty { "file.bin" }
        val dir = File(_selected.value?.dir ?: workspace.root, "attachments").apply { mkdirs() }
        val target = File(dir, safe)
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { ins -> target.outputStream().use { ins.copyTo(it) } }
            target.length()
        }.getOrNull()
        val active = activeRun.value
        val info = ensureSession()
        if (bytes == null) {
            pushItem(AgentItem.Error("Не удалось прочитать файл $name"))
            return
        }
        val rel = workspace.relativeTo(target)
        if (!canUseAgent() || !_llmConfig.value.isReady) {
            pushItem(AgentItem.Info("Файл сохранён: $rel ($bytes байт). Настройте модель — и агент сможет с ним работать."))
            return
        }
        lastPrompt = null // retry doesn't apply to attachments
        val sessionInfo = if (active != null && active.running.value) createSession() else info
        runFor(sessionInfo).messages.update { it + AgentItem.User("📎 $rel ($bytes байт)") }
        runAgentTurn(
            "The user attached the file \"$rel\" ($bytes bytes) to the project. Inspect it (read the first lines if it is text) " +
                "and briefly describe what it is and what can be done with it, then wait for instructions.",
            sessionInfo,
        )
    }

    private fun firstUserText(history: JSONArray): String? {
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            if (m.optString("role") == "user") {
                val c = m.optString("content")
                if (c.isNotBlank() && !c.startsWith("Create an AGENTS.md")) return c
            }
        }
        return null
    }

    fun cancelAgent() {
        activeRun.value?.let { run ->
            run.cancelled.set(true)
            run.release()
        }
    }

    // ---------------------------------------------------------------- projects

    init {
        refreshProjects()
        if (_projects.value.isEmpty()) createProject("hello")
        restoreAgentUi()
    }

    /**
     * Brings the agent chat back after the app was closed: the tabs that were
     * open and the active session, with history and todos. First launch (no
     * saved UI state) continues the most recent session, like `opencode -c`.
     * If the user closed all tabs on purpose, the chat starts empty.
     */
    private fun restoreAgentUi() {
        val existing = sessions.list().associateBy { it.id }
        val ui = AgentSettings.readUiState(getApplication())
        when {
            ui != null && ui.openTabs.isNotEmpty() -> {
                _openTabs.value = ui.openTabs.mapNotNull { existing[it] }
                val activeId = ui.lastSessionId?.takeIf { existing.containsKey(it) }
                    ?: _openTabs.value.firstOrNull()?.id
                if (activeId != null) existing[activeId]?.let { activate(it) }
            }
            ui == null -> {
                // nothing saved yet (first run after update): continue latest
                sessions.list().firstOrNull()?.let { latest ->
                    _openTabs.value = listOf(latest)
                    activate(latest)
                }
            }
            else -> Unit // the user closed all tabs — keep the chat empty
        }
    }

    fun refreshProjects() {
        _projects.value = currentProjects()
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
            _editorFile.value = null
            _tree.value = emptyList()
        }
        refreshProjects()
    }

    // ------------------------------------------------------------ the files

    /**
     * Rebuilds the tree, but only when the folder really changed: a fingerprint
     * of names, sizes and times is cheap enough to run every couple of seconds,
     * while building thousands of nodes is not.
     */
    private fun rebuildTree(force: Boolean = false) {
        val dir = _selected.value?.dir
        if (dir == null) {
            treePrint = ""
            if (_tree.value.isNotEmpty()) _tree.value = emptyList()
            return
        }
        val print = FileTree.fingerprint(dir)
        if (!force && print == treePrint) return
        treePrint = print
        _tree.value = FileTree.build(dir, _expandedDirs.value, _treeFilter.value)
    }

    /** Any directory walk happens off the main thread: a project can be big. */
    private fun rebuildTreeAsync(force: Boolean = false) {
        ioExecutor.execute { rebuildTree(force) }
    }

    fun refreshFiles() {
        _projects.value = currentProjects()
        rebuildTreeAsync(force = true)
    }

    private fun currentProjects(): List<Project> = workspace.projects
        .listFiles { file -> file.isDirectory }
        .orEmpty()
        .map { Project(it.name, it) }
        .sortedBy { it.name.lowercase() }

    /**
     * Watches the project folder. The agent edits files from a background thread,
     * the runtime writes output files, an import lands a photo from the gallery -
     * none of it went through the UI, and the list used to stay stale until the
     * app was restarted. The editor gets the same treatment: when the file it
     * holds changes on disk, it says so instead of quietly saving over it.
     */
    private fun startWatching() {
        if (watching) return
        watching = true
        watchExecutor.execute {
            while (watching) {
                try {
                    rebuildTree()
                    if (_projects.value.map { it.dir.name } != currentProjects().map { it.dir.name }) {
                        _projects.value = currentProjects()
                    }
                    noticeDiskChange()
                } catch (_: Throwable) {
                    // A watch that dies silently would look like "the app is broken";
                    // the next tick simply tries again.
                }
                try {
                    Thread.sleep(WATCH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@execute
                }
            }
        }
    }

    private fun stopWatching() {
        watching = false
        watchExecutor.shutdownNow()
    }

    private fun noticeDiskChange() {
        val open = _editorFile.value ?: return
        if (_diskConflict.value != null) return
        if (stampOf(open) != loadedPrint) _diskConflict.value = open
    }

    private fun stampOf(file: File): String = "${file.length()}:${file.lastModified()}"

    fun toggleFolder(file: File) {
        val path = file.absolutePath
        _expandedDirs.value =
            if (path in _expandedDirs.value) _expandedDirs.value - path else _expandedDirs.value + path
        rebuildTreeAsync()
    }

    fun setTreeQuery(query: String) {
        _treeFilter.value = _treeFilter.value.copy(query = query)
        rebuildTreeAsync()
    }

    fun setTreeKind(kind: FileKind?) {
        _treeFilter.value = _treeFilter.value.copy(kind = if (_treeFilter.value.kind == kind) null else kind)
        rebuildTreeAsync()
    }

    fun toggleHiddenFiles() {
        _treeFilter.value = _treeFilter.value.copy(showHidden = !_treeFilter.value.showHidden)
        rebuildTreeAsync()
    }

    fun clearTreeFilter() {
        _treeFilter.value = TreeFilter()
        _searchHits.value = emptyList()
        rebuildTreeAsync()
    }

    fun inspect(file: File?) {
        _inspected.value = file
        if (file == null) _historyFor.value = null
    }

    /**
     * Opens a file the way its type asks for: a folder expands, code and text go
     * to the editor, and everything else goes to the built-in viewer or, when
     * there is nothing to show it with, to a system app.
     */
    fun openFile(file: File) {
        _inspected.value = file
        val kind = FileTree.kindOf(file)
        when {
            kind == FileKind.FOLDER -> toggleFolder(file)
            FileTree.isTextual(kind) -> openInEditor(file)
            else -> showPreview(file)
        }
    }

    private fun openInEditor(file: File) {
        val project = _selected.value ?: return
        ioExecutor.execute {
            val text = FileText.read(file)
            if (text == null) {
                showNotice("«${file.name}» — не текст или больше 2 МБ")
                showPreview(file)
                return@execute
            }
            _editorFile.value = file
            _script.value = text
            loadedPrint = stampOf(file)
            _diskConflict.value = null
            _tabRequest.value = TAB_EDITOR
            // Touching a project on disk is exactly what the list must reflect.
            if (project.dir != _selected.value?.dir) rebuildTreeAsync(force = true)
        }
    }

    /** Throws away the editor text and takes what is on disk right now. */
    fun reloadFromDisk() {
        val file = _editorFile.value ?: return
        ioExecutor.execute {
            val text = FileText.read(file) ?: run {
                showNotice("файл прочитан как не текстовый")
                return@execute
            }
            _script.value = text
            loadedPrint = stampOf(file)
            _diskConflict.value = null
        }
    }

    fun createFile(name: String, folder: File? = null) {
        val dir = targetFolder(folder) ?: return
        val made = FileOps.createFile(dir, name)
        if (made == null) {
            showNotice("недопустимое имя файла")
            return
        }
        rebuildTreeAsync(force = true)
        openInEditor(made)
    }

    fun createFolder(name: String, into: File? = null) {
        val dir = targetFolder(into) ?: return
        if (FileOps.createDir(dir, name) == null) showNotice("не удалось создать папку")
        rebuildTreeAsync(force = true)
    }

    fun renameEntry(file: File, name: String) {
        val project = _selected.value ?: return
        val target = FileOps.rename(file, name)
        if (target == null) {
            showNotice("недопустимое имя")
            return
        }
        if (_editorFile.value?.absolutePath == file.absolutePath) {
            _editorFile.value = target
            loadedPrint = stampOf(target)
        }
        if (_inspected.value?.absolutePath == file.absolutePath) _inspected.value = target
        history.prune(file, project.dir)
        if (target.name != name.trim()) showNotice("имя занято, сохранено как «${target.name}»")
        rebuildTreeAsync(force = true)
    }

    fun deleteEntry(file: File) {
        val project = _selected.value ?: return
        if (file.isFile) history.snapshot(file, project.dir)
        if (!FileOps.delete(file)) {
            showNotice("не удалось удалить «${file.name}»")
            return
        }
        for (state in listOf(_editorFile, _inspected, _historyFor)) {
            if (state.value?.absolutePath == file.absolutePath) state.value = null
        }
        if (_editorFile.value == null) {
            _diskConflict.value = null
            loadedPrint = ""
            loadScriptForMode()
        }
        _preview.value = FilePreview.None
        rebuildTreeAsync(force = true)
    }

    fun moveEntry(file: File, target: File) {
        val project = _selected.value ?: return
        val moved = FileOps.move(file, target)
        if (moved == null) {
            showNotice("нельзя переместить туда")
            return
        }
        if (moved.absolutePath != file.absolutePath && project.dir == _selected.value?.dir) {
            // keep the tree honest: the old path is gone, the new one is not expanded
        }
        rebuildTreeAsync(force = true)
    }

    private fun targetFolder(folder: File?): File? {
        val project = _selected.value ?: return null
        val dir = folder?.takeIf { it.isDirectory } ?: project.dir
        return dir.takeIf { it.absolutePath.startsWith(project.dir.absolutePath) }
    }

    // ------------------------------------------------------------- importing

    /**
     * Copies what the user picked on the phone into the project. Read through the
     * content resolver, so no storage permission is involved and SAF's own
     * one-time grant is all that is needed.
     */
    fun importFromPhone(uris: List<Uri>, into: File? = null) {
        val dir = targetFolder(into) ?: return
        val resolver = getApplication<Application>().contentResolver
        ioExecutor.execute {
            var copied = 0
            var failed = 0
            for (uri in uris) {
                val name = displayName(resolver, uri)
                val target = FileOps.uniqueName(dir, FileOps.safeName(name) ?: "imported")
                try {
                    resolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { out -> copyBounded(input, out) }
                    } ?: throw IOException("не удалось открыть поток")
                    copied++
                } catch (e: IOException) {
                    target.delete()
                    failed++
                    showNotice("«$name» не импортирован: ${e.message}")
                }
            }
            if (copied > 0) showNotice("импортировано: $copied${if (failed > 0) ", пропущено: $failed" else ""}")
            rebuildTree(force = true)
        }
    }

    private fun displayName(resolver: android.content.ContentResolver, uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull() ?: "imported-${System.currentTimeMillis()}"

    /** A photo from a camera can be hundreds of megabytes; the copy stops there. */
    private fun copyBounded(input: java.io.InputStream, out: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_IMPORT_BYTES) throw IOException("файл больше ${FileText.human(MAX_IMPORT_BYTES)}")
            out.write(buffer, 0, read)
        }
        out.flush()
    }

    // --------------------------------------------------------- system apps

    fun openExternally(file: File) {
        val intent = externalIntent(file, Intent.ACTION_VIEW)
        if (intent == null) {
            showNotice("нет приложения, открывающего ${FileTree.ext(file).ifEmpty { "этот тип" }}")
            return
        }
        startIntent(intent)
    }

    fun shareFile(file: File) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = Mime.of(file)
            putExtra(Intent.EXTRA_STREAM, fileUri(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startIntent(Intent.createChooser(intent, "Поделиться ${file.name}"))
    }

    private fun externalIntent(file: File, action: String): Intent? {
        val intent = Intent(action).apply {
            setDataAndType(fileUri(file), Mime.of(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val resolved = getApplication<Application>().packageManager.resolveActivity(intent, 0)
        return if (resolved != null) intent else null
    }

    private fun fileUri(file: File): Uri = FileProvider.getUriForFile(
        getApplication(),
        "${getApplication<Application>().packageName}.files",
        file,
    )

    private fun startIntent(intent: Intent) {
        try {
            getApplication<Application>()
                .startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            showNotice("не удалось открыть: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------- previews

    private fun showPreview(file: File) {
        _inspected.value = file
        when (FileTree.kindOf(file)) {
            FileKind.IMAGE -> {
                _preview.value = FilePreview.Loading
                ioExecutor.execute {
                    val bitmap = decodeImage(file)
                    _preview.value = if (bitmap == null) {
                        FilePreview.Failed("изображение не открылось")
                    } else {
                        FilePreview.Image(bitmap, file.name)
                    }
                }
            }
            FileKind.AUDIO -> _preview.value = FilePreview.Audio(file, Mime.of(file))
            FileKind.VIDEO -> _preview.value = FilePreview.Video(file, Mime.of(file))
            else -> _preview.value = FilePreview.Other(file, FileText.human(file.length()))
        }
    }

    /** Downscaled so a 50-megapixel photo does not take the app down with it. */
    private fun decodeImage(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_IMAGE_EDGE || bounds.outHeight / (sample * 2) >= MAX_IMAGE_EDGE) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        )
    }

    fun closePreview() {
        _preview.value = FilePreview.None
    }

    // ------------------------------------------------------ content search

    fun searchContent(query: String, regex: Boolean = false, caseSensitive: Boolean = false) {
        val project = _selected.value ?: return
        if (query.isBlank()) {
            _searchHits.value = emptyList()
            return
        }
        _searching.value = true
        val hidden = _treeFilter.value.showHidden
        ioExecutor.execute {
            val hits = try {
                ContentSearch.search(project.dir, query, hidden, regex, caseSensitive)
            } catch (t: Throwable) {
                showNotice("поиск не удался: ${t.message}")
                emptyList()
            }
            _searchHits.value = hits
            _searching.value = false
            if (hits.isEmpty()) showNotice("ничего не найдено")
        }
    }

    fun clearSearch() {
        _searchHits.value = emptyList()
    }

    // -------------------------------------------------------------- history

    fun showHistory(file: File) {
        val project = _selected.value ?: return
        _historyFor.value = file
        ioExecutor.execute { _versions.value = history.versionsOf(file, project.dir) }
    }

    fun closeHistory() {
        _historyFor.value = null
        _versions.value = emptyList()
    }

    fun restoreVersion(version: Version) {
        val project = _selected.value ?: return
        val file = _historyFor.value ?: return
        ioExecutor.execute {
            if (history.restore(version, file, project.dir)) {
                showNotice("восстановлено")
                rebuildTree(force = true)
                if (_editorFile.value?.absolutePath == file.absolutePath) reloadFromDisk()
            } else {
                showNotice("не удалось восстановить")
            }
        }
    }

    /** A copy the user asked for, not one taken automatically before a write. */
    fun snapshotNow(file: File) {
        val project = _selected.value ?: return
        ioExecutor.execute {
            if (history.snapshot(file, project.dir) == null) showNotice("нечего сохранять")
        }
    }

    // --------------------------------------------------------------- notices

    private fun showNotice(message: String?) {
        _notice.value = message
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun tabRequestHandled() {
        _tabRequest.value = null
    }

    /** Called when an agent turn ends, so its writes show up without waiting. */
    fun agentTouchedFiles() {
        rebuildTreeAsync(force = true)
    }

    fun select(project: Project) {
        _selected.value = project
        _editorFile.value = null
        _inspected.value = null
        _expandedDirs.value = emptySet()
        _searchHits.value = emptyList()
        _preview.value = FilePreview.None
        _diskConflict.value = null
        loadedPrint = ""
        startWatching()
        rebuildTreeAsync(force = true)
        loadScriptForMode()
    }

    fun setEditorMode(mode: RuntimeKind) {
        editorMode.value = mode
        if (_selected.value != null) loadScriptForMode()
    }

    private fun loadScriptForMode() {
        val project = _selected.value ?: return
        // A file opened from the tree wins over the entry point: the user asked
        // for that file, and the mode chips only choose what happens without it.
        val open = _editorFile.value?.takeIf { it.isFile && it.parentFile?.let { p -> p.absolutePath.startsWith(project.dir.absolutePath) } == true }
        if (open != null) {
            ioExecutor.execute {
                val text = FileText.read(open)
                if (text == null) {
                    showNotice("«${open.name}» не текстовый или слишком большой")
                    return@execute
                }
                _script.value = text
                loadedPrint = stampOf(open)
                _diskConflict.value = null
            }
            return
        }
        val entry = entryPoint(project.dir)
        val existing = entry.takeIf { it.isFile }?.let { FileText.read(it) }
        loadedPrint = if (existing != null) stampOf(entry) else ""
        _script.value = existing ?: templateFor(editorMode.value)
        _diskConflict.value = null
    }

    private fun entryPoint(dir: File): File = when (editorMode.value) {
        RuntimeKind.NODE -> File(dir, "main.js")
        else -> File(dir, "main.py")
    }

    private fun templateFor(mode: RuntimeKind): String =
        if (mode == RuntimeKind.NODE) DEFAULT_JS_SCRIPT else DEFAULT_SCRIPT

    /**
     * Saves the editor content: the file that was opened from the tree, or the
     * mode's entry point. The old content is snapshotted first, because a save
     * overwrites whatever the agent put there.
     */
    fun saveScript(text: String) {
        val project = _selected.value ?: return
        val file = _editorFile.value ?: entryPoint(project.dir)
        history.snapshot(file, project.dir)
        try {
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
        } catch (e: IOException) {
            showNotice("не удалось сохранить: ${e.message}")
            return
        }
        loadedPrint = stampOf(file)
        _diskConflict.value = null
        rebuildTreeAsync(force = true)
    }

    fun runtimeAvailable(): Boolean = when (editorMode.value) {
        RuntimeKind.NODE, RuntimeKind.NPX -> activeNode.isAvailable()
        else -> PythonRuntime.isAvailable()
    }

    fun runtimeVersion(): String? = when (editorMode.value) {
        RuntimeKind.NODE, RuntimeKind.NPX -> activeNode.version
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
                handle = activeNode.execute(
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

    override fun onCleared() {
        stopWatching()
        // Daemon executors, but they own the app's background work; leaving
        // them running past the ViewModel keeps cancelled agent turns or a
        // half-written file alive for no reason.
        agentExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        super.onCleared()
    }

    /**
     * What the built-in viewer is showing. Files the app cannot show itself end
     * up in [Other] with a button that hands them to a system app, so nothing in
     * a project is ever unopenable.
     */
    sealed interface FilePreview {
        data object None : FilePreview
        data object Loading : FilePreview
        data class Image(val bitmap: Bitmap, val name: String) : FilePreview
        data class Audio(val file: File, val mime: String) : FilePreview
        data class Video(val file: File, val mime: String) : FilePreview
        data class Other(val file: File, val size: String) : FilePreview
        data class Failed(val message: String) : FilePreview
    }

    // ------------------------------------------------------------- updates

    /** What the updater is doing, as one screen of state. */
    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class Available(val release: ReleaseInfo) : UpdateState
        data object UpToDate : UpdateState
        data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long) : UpdateState
        data class Ready(val release: ReleaseInfo, val file: File) : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val updateChecked = AtomicBoolean(false)

    /**
     * Asks GitHub whether a newer release exists. A build that is already the
     * newest reports [UpdateState.UpToDate] and nothing else - no nagging, no
     * download behind the user's back.
     */
    fun checkForUpdate() {
        if (_updateState.value is UpdateState.Checking) return
        if (_updateState.value is UpdateState.Downloading) return
        _updateState.value = UpdateState.Checking
        ioExecutor.execute {
            try {
                val release = updater().latest()
                val newer = AppUpdate.isNewer(release.version, installedVersion())
                _updateState.value = if (newer) {
                    UpdateState.Available(release)
                } else {
                    UpdateState.UpToDate
                }
            } catch (t: Throwable) {
                _updateState.value = UpdateState.Failed(t.message ?: "проверка не удалась")
            }
        }
    }

    /**
     * Downloads the APK of the release found by [checkForUpdate]. Nothing is
     * installed from here: the file is handed to the system installer, which
     * asks the user.
     */
    fun downloadUpdate() {
        val release = (_updateState.value as? UpdateState.Available)?.release ?: return
        _updateState.value = UpdateState.Downloading(release, 0, release.asset.size)
        ioExecutor.execute {
            try {
                val file = updater().download(release.asset.url, File(updateDir(), release.asset.name)) { done, total ->
                    _updateState.value = UpdateState.Downloading(release, done, total)
                }
                _updateState.value = UpdateState.Ready(release, file)
            } catch (t: Throwable) {
                _updateState.value = UpdateState.Failed(t.message ?: "загрузка не удалась")
            }
        }
    }

    /** Opens the system installer for a downloaded APK. */
    fun installUpdate() {
        val file = (_updateState.value as? UpdateState.Ready)?.file ?: return
        val context = getApplication<Application>()
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            _updateState.value = UpdateState.Failed(t.message ?: "установщик не открылся")
        }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateState.Idle
    }

    /** The version this build reports, without the debug suffix Gradle adds. */
    fun installedVersion(): String = AppUpdate.version(BuildConfig.VERSION_NAME ?: "")

    private fun updater() =
        AppUpdater(BuildConfig.UPDATE_REPO, AgentSettings.read(getApplication()).githubToken)

    private fun updateDir(): File = File(getApplication<Application>().cacheDir, "updates")

    /**
     * One quiet check a day, once the app is up and licensed. Called from the
     * UI, never from the constructor: a network call has no business in the
     * middle of building the state this class is made of, and the license gate
     * is not known until the first composition.
     */
    fun checkUpdateOnStart() {
        if (licenseState.value !is LicenseManager.LicenseState.Active) return
        if (updateChecked.getAndSet(true)) return
        val prefs = getApplication<Application>().getSharedPreferences("updates", Context.MODE_PRIVATE)
        val last = prefs.getLong("lastCheck", 0L)
        val now = System.currentTimeMillis()
        if (now - last < CHECK_INTERVAL_MS) return
        prefs.edit().putLong("lastCheck", now).apply()
        checkForUpdate()
    }

    companion object {
        private const val MAX_OUTPUT_LINES = 2_000
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** How many sessions may run turns at the same time. */
        private const val MAX_PARALLEL_TURNS = 4

        /** How many undo steps a session keeps (and how deep redo can go). */
        private const val MAX_UNDO = 50

        /** Rough model context window used for the fill bar and auto-compaction. */
        const val CONTEXT_WINDOW_TOKENS = 128_000

        /** Squeeze the history once it reaches this share of the window. */
        const val AUTO_COMPACT_RATIO = 0.75

        /** Tokens charged for the system prompt and the tool schemas on top of the history. */
        const val CONTEXT_OVERHEAD_TOKENS = 2_000

        /** How often the project folder is checked for changes made elsewhere. */
        private const val WATCH_INTERVAL_MS = 2_000L

        /** A single import: big enough for a video, small enough for a phone. */
        private const val MAX_IMPORT_BYTES = 512L * 1024 * 1024

        /** Longest edge of a decoded image; the rest is sampled away. */
        private const val MAX_IMAGE_EDGE = 2048

        private const val TAB_EDITOR = 1

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
