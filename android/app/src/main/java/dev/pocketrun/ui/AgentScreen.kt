package dev.pocketrun.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketrun.agent.AgentSettings
import dev.pocketrun.agent.opencode.Sessions
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The opencode-style agent chat: tabs with independent sessions, a todo panel,
 * interactive question/approval cards, attachments, live round/time counters,
 * retry, copy-to-clipboard and the model settings (Zen preset, live model
 * catalog, GitHub token, permission mode, round limit).
 */
@Composable
fun AgentScreen(viewModel: AppViewModel) {
    val messages by viewModel.agentMessages.collectAsState()
    val running by viewModel.agentRunning.collectAsState()
    val config by viewModel.llmConfig.collectAsState()
    val session by viewModel.currentSession.collectAsState()
    val todos by viewModel.todos.collectAsState()
    val pendingQuestion by viewModel.pendingQuestion.collectAsState()
    val pendingApproval by viewModel.pendingApproval.collectAsState()
    val tabs by viewModel.openTabs.collectAsState()
    val steps by viewModel.agentSteps.collectAsState()
    val rounds by viewModel.agentRounds.collectAsState()
    val preview by viewModel.modelPreview.collectAsState()
    val turnStartedAt by viewModel.turnStartedAt.collectAsState()

    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    // The plan folds away on a phone; on a wide screen it has a column of its own.
    var planCollapsed by remember { mutableStateOf(false) }

    // Live "m:ss" ticker while the agent works.
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(running, turnStartedAt) {
        if (running) {
            while (true) {
                elapsed = (System.currentTimeMillis() - turnStartedAt) / 1000
                delay(1000)
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    // When a question or approval appears, show the end of the chat so the
    // card (and its buttons) is on screen.
    LaunchedEffect(pendingQuestion != null, pendingApproval != null) {
        if ((pendingQuestion != null || pendingApproval != null) && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // File attachment: any file → copied into the project and sent to the agent.
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attachFile(uri)
    }

    // The plan gets a column of its own once the screen is wide enough to spare one.
    val sidePlan = LocalConfiguration.current.screenWidthDp >= 560
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Агент", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = when {
                        !viewModel.canUseAgent() -> "требуется план Pro"
                        !config.isReady -> "модель не настроена"
                        else -> buildString {
                            append(config.model)
                            append(" · ")
                            append(AgentSettings.MODE_LABELS[config.confirmMode] ?: config.confirmMode)
                            if (config.hasGitHub) append(" · GitHub")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(
                onClick = { viewModel.refreshSessions(); showSessions = true },
                enabled = !running,
            ) {
                Icon(Icons.Filled.History, contentDescription = "Сессии")
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Настройки")
            }
        }

        // ---------------------------------------------------------------- tabs
        if (tabs.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            ) {
                tabs.forEach { tab ->
                    val selected = tab.id == session?.id
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.switchTab(tab.id) },
                        enabled = !running,
                        label = {
                            Text(
                                (if (tab.parentID != null) "⑂ " else "") + tab.title.take(18),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                IconButton(onClick = viewModel::newTab, enabled = !running) {
                    Icon(Icons.Filled.Add, contentDescription = "Новая вкладка", modifier = Modifier.size(18.dp))
                }
                if (session != null) {
                    IconButton(onClick = viewModel::closeCurrentTab, enabled = !running && tabs.size > 0) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть вкладку", modifier = Modifier.size(18.dp))
                    }
                }
            }
        } else if (!running) {
            TextButton(onClick = viewModel::newTab) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Новая вкладка (своя сессия)")
            }
        }

        // On a tablet or in landscape the plan sits in a column on the right, so it
        // no longer eats the chat's height; on a phone it stays above the chat but
        // folds away to a one-line summary until it is wanted.
        if (todos.isNotEmpty() && !sidePlan) {
            TodoPanel(todos, collapsed = planCollapsed, onToggle = { planCollapsed = !planCollapsed })
        }

        Row(Modifier.weight(1f)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
            if (messages.isEmpty()) {
                item { AgentEmptyHint() }
            }
            itemsIndexed(messages) { _, item ->
                AgentBubble(item)
            }
            if (running) {
                item {
                    Column(Modifier.padding(start = 8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "агент работает… · раунд $rounds · вызовов $steps · %d:%02d".format(elapsed / 60, elapsed % 60),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // While the model streams, show what it has written: a long
                        // generation must not look like a frozen app.
                        if (preview.isNotBlank()) {
                            Text(
                                preview.takeLast(240),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
                    if (!running && messages.lastOrNull() is AppViewModel.AgentItem.Error) {
                        item {
                            Row {
                                TextButton(onClick = viewModel::retryLast) { Text("↻ Повторить") }
                                TextButton(onClick = viewModel::continueLast) { Text("→ Продолжить (/continue)") }
                            }
                        }
                    }
                }

                pendingApproval?.let { pa ->
                    ApprovalCard(pa)
                }

                pendingQuestion?.let { pq ->
                    QuestionCard(pq)
                }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val answering = pendingQuestion != null
                    IconButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = !running && !answering,
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Отправить файл агенту")
                    }
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (answering) "Ваш ответ агенту…" else "Спросите агента… /help — справка") },
                        maxLines = 4,
                        enabled = !running || answering,
                    )
                    Spacer(Modifier.width(8.dp))
                    when {
                        // The agent is blocked on a question: the main input answers it.
                        answering -> {
                            Button(
                                onClick = {
                                    pendingQuestion?.answer?.invoke(input.ifBlank { "(нет ответа)" })
                                    input = ""
                                },
                                enabled = input.isNotBlank(),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить ответ")
                            }
                            IconButton(onClick = viewModel::cancelAgent) {
                                Icon(Icons.Filled.Stop, contentDescription = "Остановить")
                            }
                        }
                        running -> {
                            Button(onClick = viewModel::cancelAgent) {
                                Icon(Icons.Filled.Stop, contentDescription = "Остановить (сессия сохранится)")
                            }
                        }
                        else -> {
                            Button(
                                onClick = {
                                    viewModel.sendToAgent(input)
                                    input = ""
                                },
                                enabled = input.isNotBlank(),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                            }
                        }
                    }
                }
            }

            if (todos.isNotEmpty() && sidePlan) {
                TodoPanel(
                    todos = todos,
                    modifier = Modifier
                        .width(248.dp)
                        .fillMaxHeight()
                        .padding(end = 8.dp),
                )
            }
        }
    }

    if (showSessions) {
        SessionsDialog(
            viewModel = viewModel,
            onDismiss = { showSessions = false },
        )
    }

    if (showSettings) {
        LlmSettingsDialog(
            config = config,
            viewModel = viewModel,
            onDismiss = { showSettings = false },
            onSave = { viewModel.saveLlmConfig(it); showSettings = false },
        )
    }
}

@Composable
private fun AgentEmptyHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "opencode-совместимый агент: инструменты bash/read/write/edit/grep/glob,\n" +
                "apply_patch, навыки, сессии с resume и fork, инструкции AGENTS.md.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "📎 — отправить файл · /help — все команды · ⚙ — модель, GitHub, режим подтверждений",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ------------------------------------------------------------------- todos

/**
 * The agent's plan.
 *
 * It used to render only the first twelve entries with no scroll and no count, so
 * a hundred-step plan showed twelve lines and no hint that the rest existed - it
 * read as if the agent had planned twelve things and stopped. Every entry is
 * listed now, the list scrolls, and a finished step stays readable instead of
 * being dropped. Docked to the right on a wide screen, folded to a one-line
 * summary on a phone.
 */
@Composable
private fun TodoPanel(
    todos: List<Sessions.Todo>,
    modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    val done = todos.count { it.isDone }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "План задач",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "$done/${todos.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (onToggle != null) {
                    IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                            contentDescription = if (collapsed) "Показать план" else "Свернуть план",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (!collapsed) {
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(todos.size) { i ->
                        val todo = todos[i]
                        val mark = when {
                            todo.isDone -> "✓"
                            todo.isActive -> "◐"
                            else -> "○"
                        }
                        val color = if (todo.isDone) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else if (todo.isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(mark, color = color, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                todo.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (todo.isDone) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- approval

@Composable
private fun ApprovalCard(pa: AppViewModel.PendingApproval) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Разрешить инструмент «${pa.tool}»?",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (pa.argsPreview.isNotBlank()) {
                Text(
                    pa.argsPreview,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 6,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { pa.respond(false) },
                    modifier = Modifier.weight(1f),
                ) { Text("Отклонить", color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = { pa.respond(true) },
                    modifier = Modifier.weight(1f),
                ) { Text("Разрешить") }
            }
        }
    }
}

// ----------------------------------------------------------------- question

/**
 * The agent is blocked on clarifying questions. The submit button is a
 * full-width filled Button that is ALWAYS enabled; a single multiple-choice
 * question submits instantly on tap.
 */
@Composable
private fun QuestionCard(pq: AppViewModel.PendingQuestion) {
    val answers = remember(pq) { mutableStateOf(List(pq.questions.size) { "" }) }
    val instantSubmit = pq.questions.size == 1 && pq.questions.first().options.isNotEmpty()

    fun submit() {
        val text = if (pq.questions.size == 1) {
            answers.value[0].ifBlank { "(нет ответа)" }
        } else {
            pq.questions.mapIndexed { i, q -> "${i + 1}. ${q.question.take(60)} → ${answers.value[i].ifBlank { "(нет ответа)" }}" }
                .joinToString("\n")
        }
        pq.answer(text)
    }

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "🤖 Агент ждёт вашего ответа",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            // Bounded, scrollable question area — the submit button always
            // stays visible below it, even with the keyboard open.
            Column(
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pq.questions.forEachIndexed { qi, question ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        question.header?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        Text(question.question, style = MaterialTheme.typography.bodyMedium)
                        if (question.options.isNotEmpty()) {
                            Text(
                                if (instantSubmit) "Нажмите вариант — ответ уйдёт сразу:" else "Выберите вариант:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            question.options.forEach { option ->
                                val selected = answers.value[qi] == option
                                Surface(
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp),
                                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clickable {
                                            answers.value = answers.value.toMutableList().also { it[qi] = option }
                                            if (instantSubmit) pq.answer(option)
                                        },
                                ) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (selected) "◉" else "○",
                                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            option,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = answers.value[qi],
                                onValueChange = { text -> answers.value = answers.value.toMutableList().also { it[qi] = text } },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("ваш ответ…") },
                                singleLine = true,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (instantSubmit) "Ответить своим текстом" else "Ответить агенту") }
        }
    }
}

// ---------------------------------------------------------------- sessions

@Composable
private fun SessionsDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    val sessions by viewModel.sessionList.collectAsState()
    val current by viewModel.currentSession.collectAsState()
    var renaming by remember { mutableStateOf<Sessions.SessionInfo?>(null) }
    var renameText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сессии") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { viewModel.newTab(); onDismiss() }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Новая сессия (/new)")
                }
                if (sessions.isEmpty()) {
                    Text(
                        "Пока нет сохранённых сессий — каждый разговор сохраняется автоматически.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        Surface(
                            color = if (s.id == current?.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(
                                    s.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    "${s.project ?: "—"} · ${SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(s.updatedAt))}" +
                                        (s.parentID?.let { " · форк" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row {
                                    TextButton(onClick = { viewModel.switchTab(s.id); onDismiss() }) { Text("Открыть") }
                                    TextButton(onClick = { viewModel.forkSession(s.id) }) { Text("Форк") }
                                    TextButton(onClick = { renaming = s; renameText = s.title }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Переименовать", modifier = Modifier.size(14.dp))
                                    }
                                    TextButton(onClick = { viewModel.exportSession(s.id) }) {
                                        Icon(Icons.Filled.Download, contentDescription = "Экспорт", modifier = Modifier.size(14.dp))
                                    }
                                    TextButton(onClick = { viewModel.deleteSession(s.id) }) {
                                        Text("✕", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )

    renaming?.let { s ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Переименовать сессию") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.renameSession(s.id, renameText); renaming = null }) { Text("ОК") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } },
        )
    }
}

// ---------------------------------------------------------------- bubbles

/**
 * A chat bubble that can be read, selected and copied.
 *
 * Two ways to copy, because they answer different needs. The button copies the
 * whole message, which is what you want to paste into a bug report. Dragging over
 * the text selects it and the system toolbar copies only the selection. They used
 * to be one gesture - a long press on the bubble - which always copied the entire
 * message no matter how much of it was highlighted, and because the surface
 * swallowed the gesture there was no way to select a few lines at all.
 */
@Composable
private fun CopyableSurface(
    contentAlignment: Alignment,
    shape: RoundedCornerShape,
    color: androidx.compose.ui.graphics.Color,
    text: String,
    content: @Composable () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth(), contentAlignment = contentAlignment) {
        Surface(
            color = color,
            shape = shape,
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column {
                SelectionContainer(Modifier.fillMaxWidth()) { content() }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(context, "Сообщение скопировано", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Скопировать сообщение",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentBubble(item: AppViewModel.AgentItem) {
    when (item) {
        is AppViewModel.AgentItem.User -> CopyableSurface(
            contentAlignment = Alignment.CenterEnd,
            shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            text = item.text,
        ) {
            Text(item.text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        is AppViewModel.AgentItem.Assistant -> CopyableSurface(
            contentAlignment = Alignment.CenterStart,
            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            text = item.text,
        ) {
            Text(
                item.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        is AppViewModel.AgentItem.Error -> CopyableSurface(
            contentAlignment = Alignment.CenterStart,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            text = item.text,
        ) {
            Text(
                item.text,
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        is AppViewModel.AgentItem.Info -> Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        is AppViewModel.AgentItem.Tool -> ToolRow(item)
    }
}

/** A tool call: selectable text, and a button that copies just this call. */
@Composable
private fun ToolRow(item: AppViewModel.AgentItem.Tool) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val full = "🛠 ${item.name} ${item.args}\n${item.result ?: ""}"
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            SelectionContainer(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "🛠 ${item.name}${if (item.args.isNotBlank()) " · ${item.args.take(120)}" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    if (item.result != null && item.result.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.result.take(400) + if (item.result.length > 400) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(full))
                        Toast.makeText(context, "Вызов скопирован", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Скопировать вызов",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LlmSettingsDialog(
    config: AgentSettings.Config,
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
    onSave: (AgentSettings.Config) -> Unit,
) {
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var model by remember { mutableStateOf(config.model) }
    var githubToken by remember { mutableStateOf(config.githubToken) }
    var confirmMode by remember { mutableStateOf(config.confirmMode) }
    var maxSteps by remember { mutableStateOf(config.safeMaxSteps.toString()) }
    val catalog by viewModel.modelCatalog.collectAsState()
    val catalogState by viewModel.modelCatalogState.collectAsState()
    val ctx = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки агента") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // ---- model
                Text("Модель", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AgentSettings.PRESETS.forEach { preset ->
                        FilterChip(
                            selected = baseUrl == preset.baseUrl,
                            onClick = {
                                baseUrl = preset.baseUrl
                                if (model.isBlank() || baseUrl != config.baseUrl) model = preset.model
                            },
                            label = { Text(preset.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    supportingText = {
                        Text(
                            if (baseUrl.contains("opencode.ai/zen")) "ключ бесплатный на opencode.ai/auth — нужен даже бесплатным моделям"
                            else "например https://api.openai.com/v1",
                        )
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API-ключ (не обязателен)") },
                    supportingText = {
                        if (apiKey.isBlank()) Text("без ключа — только локальные серверы; Zen ответит 403")
                    },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                if (baseUrl.contains("opencode.ai/zen")) {
                    TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://opencode.ai/auth"),
                                ),
                            )
                        }
                    }) { Text("Получить бесплатный ключ → opencode.ai/auth") }
                }
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Модель") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { viewModel.fetchModelCatalog(baseUrl, apiKey) },
                        enabled = baseUrl.isNotBlank(),
                    ) { Text("Загрузить модели") }
                    if (catalogState != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            catalogState ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (catalog.isNotEmpty()) {
                    Text(
                        "Найдено моделей: ${catalog.size} — нажмите, чтобы выбрать",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                        items(catalog) { id ->
                            Text(
                                id,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (id == model) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { model = id }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }

                // ---- behaviour
                Text("Поведение", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AgentSettings.MODES.forEach { mode ->
                        FilterChip(
                            selected = confirmMode == mode,
                            onClick = { confirmMode = mode },
                            label = { Text(AgentSettings.MODE_LABELS[mode] ?: mode) },
                        )
                    }
                }
                Text(
                    "Авто — инструменты выполняются сразу · Вручную — изменения (файлы, bash, GitHub) требуют подтверждения · Вопросы — агент сначала задаёт уточняющие вопросы",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = maxSteps,
                    onValueChange = { maxSteps = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Раундов на ход (1–${AgentSettings.MAX_STEPS_LIMIT})") },
                    supportingText = {
                        Text("сколько model-раундов агент может сделать за один запрос (по умолчанию ${AgentSettings.DEFAULT_MAX_STEPS})")
                    },
                    singleLine = true,
                )

                // ---- github
                Text("GitHub", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = githubToken,
                    onValueChange = { githubToken = it },
                    label = { Text("GitHub PAT (не обязателен)") },
                    supportingText = { Text("даёт агенту инструмент github: репозитории, Actions-раннеры (npm, opencode), логи, артефакты") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                if (githubToken.isBlank()) {
                    TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(AgentSettings.GITHUB_TOKEN_URL),
                                ),
                            )
                        }
                    }) { Text("Создать токен (repo + workflow) → github.com") }
                }
                Text(
                    "Ключи хранятся только на устройстве: модель отправляется указанному LLM-серверу, GitHub-токен — только api.github.com.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val steps = maxSteps.toIntOrNull()?.coerceIn(1, AgentSettings.MAX_STEPS_LIMIT) ?: AgentSettings.DEFAULT_MAX_STEPS
                    onSave(
                        AgentSettings.Config(
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            model = model,
                            githubToken = githubToken,
                            confirmMode = confirmMode,
                            maxSteps = steps,
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
