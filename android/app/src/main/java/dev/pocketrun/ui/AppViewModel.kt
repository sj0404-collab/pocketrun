package dev.pocketrun.ui

import android.app.Application
import android.content.Context
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import dev.pocketrun.BuildConfig
import dev.pocketrun.agent.AgentSettings
import dev.pocketrun.agent.LlmClient
import dev.pocketrun.agent.opencode.OpenCodeAgent
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
import dev.pocketrun.runtime.js.JsRuntime
import dev.pocketrun.runtime.npm.NpxRuntime
import dev.pocketrun.runtime.python.PythonRuntime
import dev.pocketrun.update.AppUpdate
import dev.pocketrun.update.AppUpdater
import dev.pocketrun.update.ReleaseInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
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

    val npxRuntime: NpxRuntime by lazy { NpxRuntime(jsRuntime, workspace) }

    // ---------------------------------------------------------------- agent

    /** One bubble / row in the agent conversation. */
    sealed interface AgentItem {
        data class User(val text: String) : AgentItem
        data class Assistant(val text: String) : AgentItem
        data class Tool(val name: String, val args: String, val result: String?) : AgentItem
        data class Error(val text: String) : AgentItem
        data class Info(val text: String) : AgentItem

        /**
         * Something the agent produced that can be shown, not just read: a picture,
         * a sound, a video, an animation. Carries the prompt it was made from when
         * one is known, so the card can offer to try again with different wording.
         */
        data class Media(val file: File, val kind: FileKind, val prompt: String?) : AgentItem
    }

    private val _agentMessages = MutableStateFlow<List<AgentItem>>(emptyList())
    val agentMessages: StateFlow<List<AgentItem>> = _agentMessages.asStateFlow()

    private val _agentRunning = MutableStateFlow(false)
    val agentRunning: StateFlow<Boolean> = _agentRunning.asStateFlow()

    private val _llmConfig = MutableStateFlow(AgentSettings.read(app))
    val llmConfig: StateFlow<AgentSettings.Config> = _llmConfig.asStateFlow()

    /** Sessions: the opencode-style local session store. */
    val sessions = Sessions(workspace)

    private val _sessionList = MutableStateFlow<List<Sessions.SessionInfo>>(emptyList())
    val sessionList: StateFlow<List<Sessions.SessionInfo>> = _sessionList.asStateFlow()

    private val _currentSession = MutableStateFlow<Sessions.SessionInfo?>(null)
    val currentSession: StateFlow<Sessions.SessionInfo?> = _currentSession.asStateFlow()

    private val _todos = MutableStateFlow<List<Sessions.Todo>>(emptyList())
    val todos: StateFlow<List<Sessions.Todo>> = _todos.asStateFlow()

    /** A question the agent is blocked on, waiting for the user's answer. */
    data class PendingQuestion(
        val questions: List<OpenCodeTools.Question>,
        val answer: (String) -> Unit,
    )

    private val _pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?> = _pendingQuestion.asStateFlow()

    /** Model catalog loaded from the configured server (GET /models). */
    private val _modelCatalog = MutableStateFlow<List<String>>(emptyList())
    val modelCatalog: StateFlow<List<String>> = _modelCatalog.asStateFlow()

    private val _modelCatalogState = MutableStateFlow<String?>(null)
    val modelCatalogState: StateFlow<String?> = _modelCatalogState.asStateFlow()

    /** Open tabs — each tab is its own agent session (browser-style). */
    private val _openTabs = MutableStateFlow<List<Sessions.SessionInfo>>(emptyList())
    val openTabs: StateFlow<List<Sessions.SessionInfo>> = _openTabs.asStateFlow()

    /** A mutating tool call the agent is blocked on in manual confirm mode. */
    data class PendingApproval(val tool: String, val argsPreview: String, val respond: (Boolean) -> Unit)

    private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
    val pendingApproval: StateFlow<PendingApproval?> = _pendingApproval.asStateFlow()

    /** Live counters of the running turn: tool calls done and model rounds used. */
    private val _agentSteps = MutableStateFlow(0)
    val agentSteps: StateFlow<Int> = _agentSteps.asStateFlow()

    private val _agentRounds = MutableStateFlow(0)
    val agentRounds: StateFlow<Int> = _agentRounds.asStateFlow()

    /** What the model is streaming right now, so a long generation is not silence. */
    private val _modelPreview = MutableStateFlow("")
    val modelPreview: StateFlow<String> = _modelPreview.asStateFlow()

    private val _turnStartedAt = MutableStateFlow(0L)
    val turnStartedAt: StateFlow<Long> = _turnStartedAt.asStateFlow()

    /** Last user prompt, for the retry button. */
    @Volatile
    private var lastPrompt: String? = null

    private val agentExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pocketrun-agent").apply { isDaemon = true } }

    private val ioExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pocketrun-io").apply { isDaemon = true } }

    private val agentCancelled = AtomicBoolean(false)

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
        if (_agentRunning.value) return
        _agentMessages.value = emptyList()
        _todos.value = emptyList()
        _pendingQuestion.value = null
    }

    fun refreshSessions() {
        _sessionList.value = sessions.list()
    }

    private fun projectName(): String? = _selected.value?.name

    /** Loads a session into the chat without the running-guard (startup, tabs). */
    private fun showSession(id: String) {
        val session = sessions.load(id) ?: return
        _currentSession.value = session.info
        _todos.value = session.todos
        _agentMessages.value = historyToItems(session.messages)
    }

    /** Remembers the active tab and open tabs for the next app launch. */
    private fun persistAgentUi() {
        AgentSettings.saveUiState(getApplication(), _currentSession.value?.id, _openTabs.value.map { it.id })
    }

    /** `/new` — starts a fresh session for the current project. */
    fun newSession() {
        if (_agentRunning.value) return
        val session = sessions.create(project = projectName(), title = null, model = _llmConfig.value.model)
        _currentSession.value = session.info
        _agentMessages.value = emptyList()
        _todos.value = emptyList()
        registerTab(session.info)
        refreshSessions()
        persistAgentUi()
    }

    /** Resumes a stored session: history and todos come back into the chat. */
    fun openSession(id: String) {
        if (_agentRunning.value) return
        showSession(id)
        refreshSessions()
        persistAgentUi()
    }

    fun forkSession(id: String) {
        if (_agentRunning.value) return
        sessions.fork(id)?.let { child ->
            _currentSession.value = child.info
            _todos.value = child.todos
            _agentMessages.value = historyToItems(child.messages)
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
        sessions.delete(id)
        _openTabs.value = _openTabs.value.filterNot { it.id == id }
        if (_currentSession.value?.id == id) {
            val next = _openTabs.value.firstOrNull()
            if (next != null) showSession(next.id) else {
                _currentSession.value = null
                _agentMessages.value = emptyList()
                _todos.value = emptyList()
            }
        }
        refreshSessions()
        persistAgentUi()
    }

    /** `opencode export` analog: writes the session to opencode-data/exports/. */
    fun exportSession(id: String) {
        val path = sessions.export(id)
        _agentMessages.update {
            it + AgentItem.Info(
                if (path != null) "Сессия сохранена: $path"
                else "Не удалось экспортировать сессию",
            )
        }
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

    fun sendToAgent(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _agentRunning.value) return

        // Chat commands, opencode-style.
        when (prompt) {
            "/new" -> {
                newSession()
                _agentMessages.update { it + AgentItem.Info("Новая сессия${projectName()?.let { " для проекта $it" } ?: ""}.") }
                return
            }
            "/init" -> {
                if (!requireConfigured()) return
                _agentMessages.update { it + AgentItem.User("/init") }
                runAgentTurn(
                    "Create an AGENTS.md file in the current project root: briefly describe the project " +
                        "(look at the files first) and add short, useful rules for future agents working in it. " +
                        "Keep it under 30 lines. Reply with a one-line summary.",
                )
                return
            }
            "/continue" -> {
                if (!requireConfigured()) return
                _agentMessages.update { it + AgentItem.User("/continue") }
                runAgentTurn(OpenCodeAgent.CONTINUE_PROMPT)
                return
            }
            "/skills" -> {
                val found = dev.pocketrun.agent.opencode.Skills(workspace).discover(_selected.value?.dir)
                _agentMessages.update {
                    it + AgentItem.Info(
                        if (found.isEmpty()) "Навыков не найдено. Создайте свой: папка .opencode/skills/<имя>/SKILL.md в проекте с frontmatter name и description (или попросите агента — он умеет создавать навыки сам)."
                        else "Навыки:\n" + found.joinToString("\n") { s -> "• ${s.name}${if (s.global) " (глобальный)" else ""} — ${s.description}" },
                    )
                }
                return
            }
            "/help" -> {
                _agentMessages.update {
                    it + AgentItem.Info(
                        "Команды: /new — новая сессия · /init — создать AGENTS.md · /continue — продолжить прерванный ход · /skills — список навыков · /help — эта справка.\n\n" +
                            "Навыки: создайте .opencode/skills/<имя>/SKILL.md (frontmatter: name, description) — агент подхватит и сможет загружать; глобальные — workspace/opencode/skills/.\n" +
                            "Инструкции: AGENTS.md в проекте + глобальный opencode/AGENTS.md, фолбэк CLAUDE.md; список файлов/URL — в opencode/opencode.json (\"instructions\").\n" +
                            "GitHub: ⚙ → GitHub (PAT с правами repo, workflow) — агент сможет создавать репозитории, Actions-раннеры (ubuntu-latest, там работают npm и opencode), запускать сборку и ждать её через github run_wait (одним вызовом, с логами при падении), забирать артефакты.\n" +
                            "Подтверждения и лимит раундов: ⚙ → «Подтверждения» и «Раундов на ход». Долгие сборки и ожидание — это нормально: раундов по умолчанию 40.\n" +
                            "Файл агенту: кнопка 📎 — файл копируется в проект и отправляется агенту.\n" +
                            "Остановка: ⏹ — ход прерывается, но сессия и история сохраняются (/continue продолжает).",
                    )
                }
                return
            }
        }

        if (!requireConfigured()) return
        lastPrompt = prompt
        _agentMessages.update { it + AgentItem.User(prompt) }
        runAgentTurn(prompt)
    }

    /** Re-runs the last prompt (shown next to errors). */
    fun retryLast() {
        val p = lastPrompt ?: return
        if (_agentRunning.value || p.isEmpty()) return
        if (!requireConfigured()) return
        _agentMessages.update { it + AgentItem.User("$p (повтор)") }
        runAgentTurn(p)
    }

    /**
     * Picks a stopped turn back up: the history, todos and every file the
     * agent already touched are in place, so "продолжай" is all it needs.
     */
    fun continueLast() {
        if (_agentRunning.value) return
        if (!requireConfigured()) return
        _agentMessages.update { it + AgentItem.User("/continue") }
        runAgentTurn(OpenCodeAgent.CONTINUE_PROMPT)
    }

    private fun requireConfigured(): Boolean {
        if (!canUseAgent()) {
            _agentMessages.update { it + AgentItem.Error("Агент доступен на плане Pro — активируйте Pro-лицензию.") }
            return false
        }
        val config = _llmConfig.value
        if (!config.isReady) {
            _agentMessages.update { it + AgentItem.Info("Сначала настройте модель: базовый URL и имя модели (кнопка ⚙ над чатом). API-ключ не нужен для локальных серверов; для Zen — бесплатный ключ с opencode.ai/auth.") }
            return false
        }
        return true
    }

    private fun runAgentTurn(userText: String) {
        val config = _llmConfig.value
        if (!config.isReady) return
        _agentRunning.value = true
        agentCancelled.set(false)
        _agentSteps.value = 0
        _agentRounds.value = 0
        _modelPreview.value = ""
        _turnStartedAt.value = System.currentTimeMillis()
        val turnStart = _turnStartedAt.value
        lastUserPrompt = userText

        agentExecutor.execute {
            try {
                val projectDir = _selected.value?.dir
                val tools = OpenCodeTools(workspace, jsRuntime, npxRuntime, projectDir, config.githubToken)
                val agent = OpenCodeAgent(
                    workspace, tools, { LlmClient(config) }, projectDir,
                    maxSteps = config.safeMaxSteps,
                    askMode = config.confirmMode == AgentSettings.MODE_ASK,
                    hasGitHub = config.hasGitHub,
                    isCancelled = { agentCancelled.get() },
                )

                // The session this turn belongs to; create one on demand.
                val session = _currentSession.value?.let { sessions.load(it.id) }
                    ?: sessions.create(project = projectName(), title = null, model = config.model)
                registerTab(session.info)

                // Question tool: block this thread until the user answers in the UI.
                tools.questionAsker = { questions ->
                    val latch = CountDownLatch(1)
                    var answer: String? = null
                    _pendingQuestion.value = PendingQuestion(questions) { text ->
                        answer = text
                        _pendingQuestion.value = null
                        latch.countDown()
                    }
                    if (latch.await(10, TimeUnit.MINUTES)) {
                        answer ?: "(нет ответа)"
                    } else {
                        _pendingQuestion.value = null
                        "(ответа не последовало)"
                    }
                }

                // Manual mode: mutating tool calls wait for a tap in the UI.
                if (config.confirmMode == AgentSettings.MODE_MANUAL) {
                    tools.toolApprover = { name, argsJson ->
                        val latch = CountDownLatch(1)
                        var approved = false
                        _pendingApproval.value = PendingApproval(name, argsJson.take(300)) { ok ->
                            approved = ok
                            _pendingApproval.value = null
                            latch.countDown()
                        }
                        if (latch.await(10, TimeUnit.MINUTES)) {
                            approved
                        } else {
                            _pendingApproval.value = null
                            false
                        }
                    }
                }

                tools.onTodos = { list ->
                    session.todos = list
                    _todos.value = list
                }
                tools.todos = session.todos
                // A waiting tool (github run_wait) must notice ⏹ as well.
                tools.cancelCheck = { agentCancelled.get() }

                val newHistory = agent.run(userText, session.messages) { event ->
                    when (event) {
                        is OpenCodeAgent.Event.Round -> _agentRounds.value = event.step
                        is OpenCodeAgent.Event.Thinking -> _modelPreview.value = event.preview
                        is OpenCodeAgent.Event.Note ->
                            _agentMessages.update { it + AgentItem.Info(event.text) }
                        is OpenCodeAgent.Event.AssistantText ->
                            _agentMessages.update { it + AgentItem.Assistant(event.text) }
                        is OpenCodeAgent.Event.ToolStart -> {
                            _agentSteps.value = _agentSteps.value + 1
                            _agentMessages.update { it + AgentItem.Tool(event.name, event.args, null) }
                        }
                        is OpenCodeAgent.Event.ToolDone ->
                            _agentMessages.update { items ->
                                val idx = items.indexOfLast { it is AgentItem.Tool && it.result == null && it.name == event.name }
                                if (idx >= 0) items.toMutableList().also { list -> list[idx] = (list[idx] as AgentItem.Tool).copy(result = event.result) }
                                else items + AgentItem.Tool(event.name, "", event.result)
                            }
                        is OpenCodeAgent.Event.Failed -> {
                            _agentMessages.update { it + AgentItem.Error(event.message) }
                            // Zen refuses keyless requests with 403 even for free
                            // models — tell the user where the free key lives.
                            if (config.isZen && "403" in event.message) {
                                _agentMessages.update {
                                    it + AgentItem.Info(
                                        "Zen требует ключ даже для бесплатных моделей — получите его бесплатно на opencode.ai/auth " +
                                            "(кнопка ⚙ над чатом). Бесплатные модели не расходуют кредиты.",
                                    )
                                }
                            }
                        }
                    }
                }

                // Persist the turn: history, todos, auto-title from the first
                // message. Runs even after cancellation — memory is preserved.
                session.messages = newHistory
                session.info.model = config.model
                if (session.info.title == "Новая сессия") {
                    val firstUser = firstUserText(newHistory) ?: userText
                    val auto = firstUser.replace('\n', ' ').trim().take(40).let { if (it.length == 40) it + "…" else it }
                    if (auto.isNotBlank()) session.info.title = auto
                }
                sessions.save(session)
                _currentSession.value = session.info
                refreshSessions()
                persistAgentUi()
                val secs = (System.currentTimeMillis() - turnStart) / 1000
                if (_agentRounds.value > 0 || agentCancelled.get()) {
                    _agentMessages.update {
                        it + AgentItem.Info(
                            "⏱ раундов: ${_agentRounds.value} · инструментов: ${_agentSteps.value} · " +
                                "время: ${secs}с · сессия сохранена",
                        )
                    }
                }
            } catch (t: Throwable) {
                // Anything unexpected must not leave the chat stuck on "working".
                _agentMessages.update { it + AgentItem.Error(t.message ?: t.javaClass.simpleName) }
            } finally {
                _modelPreview.value = ""
                _pendingQuestion.value = null
                _pendingApproval.value = null
                _agentRunning.value = false
                // The agent writes files itself; the tree must not wait for the
                // next poll to show what it did, and anything it produced that can
                // be seen or heard belongs in the conversation, not only in the
                // file tree.
                agentTouchedFiles()
                publishTurnMedia(turnStart)
            }
        }
    }

    // ---------------------------------------------------------------- tabs

    private fun registerTab(info: Sessions.SessionInfo) {
        _openTabs.update { tabs -> if (tabs.any { it.id == info.id }) tabs.map { if (it.id == info.id) info else it } else tabs + info }
    }

    /** Opens (or switches to) a session tab. */
    fun switchTab(id: String) {
        if (_agentRunning.value) return
        showSession(id)
        val info = _sessionList.value.firstOrNull { it.id == id }
            ?: _openTabs.value.firstOrNull { it.id == id }
            ?: sessions.load(id)?.info
            ?: return
        registerTab(info)
        refreshSessions()
        persistAgentUi()
    }

    /** A fresh tab with its own new session. */
    fun newTab() {
        if (_agentRunning.value) return
        newSession() // registers the tab and persists itself
    }

    /** Closes the active tab and shows the neighbour (or an empty chat). */
    fun closeCurrentTab() {
        if (_agentRunning.value) return
        val current = _currentSession.value ?: return
        val tabs = _openTabs.value
        val idx = tabs.indexOfFirst { it.id == current.id }
        _openTabs.value = tabs.filterNot { it.id == current.id }
        val next = tabs.getOrNull(idx + 1) ?: tabs.getOrNull(idx - 1)
        if (next != null) {
            showSession(next.id)
        } else {
            _currentSession.value = null
            _agentMessages.value = emptyList()
            _todos.value = emptyList()
        }
        persistAgentUi()
    }

    // ---------------------------------------------------------------- files

    /**
     * Sends a file to the agent: copies it into the project's attachments/
     * folder and starts a turn so the agent can work with it (read/bash).
     */
    fun attachFile(uri: android.net.Uri) {
        if (_agentRunning.value) return
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
        if (bytes == null) {
            _agentMessages.update { it + AgentItem.Error("Не удалось прочитать файл $name") }
            return
        }
        val rel = workspace.relativeTo(target)
        if (!canUseAgent() || !_llmConfig.value.isReady) {
            _agentMessages.update { it + AgentItem.Info("Файл сохранён: $rel ($bytes байт). Настройте модель — и агент сможет с ним работать.") }
            return
        }
        lastPrompt = null // retry doesn't apply to attachments
        _agentMessages.update { it + AgentItem.User("📎 $rel ($bytes байт)") }
        runAgentTurn(
            "The user attached the file \"$rel\" ($bytes bytes) to the project. Inspect it (read the first lines if it is text) " +
                "and briefly describe what it is and what can be done with it, then wait for instructions.",
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
        agentCancelled.set(true)
        _pendingQuestion.value?.answer?.invoke("(отменено пользователем)")
        _pendingApproval.value?.respond?.invoke(false)
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
                if (activeId != null) showSession(activeId)
            }
            ui == null -> {
                // nothing saved yet (first run after update): continue latest
                sessions.list().firstOrNull()?.let { latest ->
                    _openTabs.value = listOf(latest)
                    showSession(latest.id)
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
     * Rebuilds the tree, but only when the result would actually differ: a
     * fingerprint of names, sizes and times is cheap enough to run every couple
     * of seconds, while building thousands of nodes is not.
     *
     * The fingerprint alone is not enough to decide that. It describes the disk
     * and says nothing about what the user asked to see, so opening a folder or
     * typing in the filter - both of which change the answer without touching a
     * single byte on disk - compared equal to the previous stamp and the tree
     * was never rebuilt. That is why "Открыть папку" did nothing: the row
     * flipped to an open-folder icon with no children under it. The view state
     * is therefore part of the stamp.
     */
    private fun rebuildTree(force: Boolean = false) {
        val dir = _selected.value?.dir
        if (dir == null) {
            treePrint = ""
            if (_tree.value.isNotEmpty()) _tree.value = emptyList()
            return
        }
        val expanded = _expandedDirs.value
        val filter = _treeFilter.value
        val print = FileTree.stamp(dir, expanded, filter)
        if (!force && print == treePrint) return
        treePrint = print
        _tree.value = FileTree.build(dir, expanded, filter)
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

    /** Taps a row: a folder folds and unfolds, anything else opens. */
    fun activate(file: File) {
        if (file.isDirectory) toggleFolder(file) else openFile(file)
    }

    fun toggleFolder(file: File) {
        val path = file.absolutePath
        _expandedDirs.value =
            if (path in _expandedDirs.value) _expandedDirs.value - path else _expandedDirs.value + path
        rebuildTreeAsync()
    }

    /**
     * The "Открыть папку" button. Unlike a row tap this never folds a folder that
     * is already open - pressing "open" on something that is open used to close
     * it, because both paths ran the same toggle.
     */
    fun openFolder(file: File) {
        if (file.absolutePath !in _expandedDirs.value) {
            _expandedDirs.value = _expandedDirs.value + file.absolutePath
            rebuildTreeAsync()
        }
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
            kind == FileKind.FOLDER -> openFolder(file)
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

    /** Launches [intent]; false when nothing on the device can handle it. */
    private fun startIntent(intent: Intent): Boolean = try {
        getApplication<Application>()
            .startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (t: Throwable) {
        showNotice("не удалось открыть: ${t.message ?: t.javaClass.simpleName}")
        false
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
                        FilePreview.Image(bitmap, file.name, file)
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

    // ------------------------------------------------------------ chat media

    /**
     * Shows what the agent just produced that can be seen or heard.
     *
     * The agent's writes are text-only as far as the chat is concerned: a picture
     * it saved appeared in the file tree, but nothing in the conversation, and the
     * user had to go looking for it. So at the end of a turn the project folder is
     * scanned for media that appeared or changed during it, and each one becomes
     * a card in the chat.
     *
     * Files that are already on screen as a card are not repeated, and anything
     * the user picked up before the turn started is left alone.
     */
    private fun publishTurnMedia(since: Long) {
        val project = _selected.value?.dir ?: workspace.root
        if (!project.isDirectory) return
        val shown = _agentMessages.value
            .filterIsInstance<AgentItem.Media>()
            .map { it.file.absolutePath }
            .toSet()

        val found = mutableListOf<File>()
        var count = 0
        val stack = ArrayDeque<File>()
        stack.addLast(project)
        while (stack.isNotEmpty() && count < 4_000) {
            val dir = stack.removeLast()
            for (child in dir.listFiles().orEmpty()) {
                count++
                val name = child.name
                if (name.startsWith(".")) continue
                if (child.isDirectory) {
                    // node_modules and build output are the agent's dependencies,
                    // not things it made for the user to look at.
                    if (name !in FileTree.SKIP_DIRS && name !in MEDIA_SKIP_DIRS) stack.addLast(child)
                    continue
                }
                val kind = FileTree.kindOf(child)
                if (kind != FileKind.IMAGE && kind != FileKind.AUDIO && kind != FileKind.VIDEO) continue
                if (child.absolutePath in shown) continue
                if (child.length() < 512) continue
                // "Changed during this turn" is the honest test: a file whose mtime
                // predates the turn was already there.
                if (child.lastModified() + 2_000 < since) continue
                found += child
                if (found.size >= 6) break
            }
            if (found.size >= 6) break
        }
        if (found.isEmpty()) return

        val prompt = lastUserPrompt
        _agentMessages.update { items ->
            found.sortedBy { it.lastModified() }.fold(items) { acc, file ->
                acc + AgentItem.Media(file, FileTree.kindOf(file), prompt)
            }
        }
    }

    /** The wording that produced a file, so "try again" can offer a different one. */
    private var lastUserPrompt: String? = null

    /** Directories whose contents are build output, not things to show. */
    private val MEDIA_SKIP_DIRS = setOf("out", "dist", "coverage", "cache", "tmp", ".git")

    /** Sends a follow-up about a media file: regenerate, or reword and retry. */
    fun askAboutMedia(file: File, instruction: String) {
        val prompt = lastUserPrompt
        val message = buildString {
            append(instruction).append(' ').append(file.name)
            if (prompt != null) append(" (прежний запрос: ").append(prompt).append(')')
            append(". ")
            append("Посмотри файл в проекте и сделай новую версию; если не можешь создавать такой файл сам — скажи прямо.")
        }
        sendToAgent(message)
    }

    /** Opens a media file from the chat in the built-in viewer. */
    fun openMedia(file: File) = showPreview(file)

    /**
     * Opens the system wallpaper picker at this app's entry.
     *
     * The app cannot set a wallpaper itself without the user confirming it in the
     * system UI, so this only launches that screen; everything after it belongs
     * to Android.
     */
    fun openWallpaperPicker() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                "android.service.wallpaper.EXTRA_LIVE_WALLPAPER_COMPONENT",
                ComponentName(getApplication(), "dev.pocketrun.wallpaper.LiveWallpaperService"),
            )
        }
        if (!startIntent(intent)) {
            // Not every device or launcher offers the live wallpaper picker.
            startIntent(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    /** Copies a produced file out of the sandbox to somewhere the user chooses. */
    fun exportMedia(file: File) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = Mime.of(file)
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
        _exportSource.value = file
        startIntent(intent)
    }

    private val _exportSource = MutableStateFlow<File?>(null)

    /** Finishes a [exportMedia] hand-off once the system picker returns a target. */
    fun completeExport(target: Uri) {
        val source = _exportSource.value ?: return
        _exportSource.value = null
        ioExecutor.execute {
            val ok = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(target)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
            }.getOrNull()
            _agentMessages.update {
                it + if (ok != null) {
                    AgentItem.Info("Сохранено: ${source.name}")
                } else {
                    AgentItem.Error("Не удалось сохранить ${source.name}")
                }
            }
        }
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

    override fun onCleared() {
        stopWatching()
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
        data class Image(val bitmap: Bitmap, val name: String, val file: File? = null) : FilePreview
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
