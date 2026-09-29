package dev.pocketrun.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import dev.pocketrun.BuildConfig
import dev.pocketrun.agent.AgentSettings
import dev.pocketrun.agent.LlmClient
import dev.pocketrun.agent.opencode.OpenCodeAgent
import dev.pocketrun.agent.opencode.OpenCodeTools
import dev.pocketrun.agent.opencode.Sessions
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
        checkUpdateInBackground()
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
     * One quiet check a day, on launch, and only once the app is licensed:
     * nobody should wait on a network call to reach the projects list.
     */
    private fun checkUpdateInBackground() {
        if (updateChecked.getAndSet(true)) return
        if (licenseState.value !is LicenseManager.LicenseState.Active) return
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
