package dev.pocketrun.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketrun.agent.AgentSettings

/**
 * The opencode-style agent chat: sessions with resume/fork/export, a todo
 * panel, interactive question cards and the model settings (with the OpenCode
 * Zen preset and a live model catalog).
 */
@Composable
fun AgentScreen(viewModel: AppViewModel) {
    val messages by viewModel.agentMessages.collectAsState()
    val running by viewModel.agentRunning.collectAsState()
    val config by viewModel.llmConfig.collectAsState()
    val session by viewModel.currentSession.collectAsState()
    val todos by viewModel.todos.collectAsState()
    val pendingQuestion by viewModel.pendingQuestion.collectAsState()

    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

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
                        else -> (session?.title?.takeIf { it != "Новая сессия" }?.let { "$it · " } ?: "") + config.model
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
                Icon(Icons.Filled.Settings, contentDescription = "Настройки модели")
            }
        }

        if (todos.isNotEmpty()) {
            TodoPanel(todos)
        }

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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "агент работает…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        pendingQuestion?.let { pq ->
            QuestionCard(pq)
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Спросите агента… /new — новая сессия, /init — создать AGENTS.md") },
                maxLines = 4,
                enabled = !running,
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                Button(onClick = viewModel::cancelAgent) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                }
            } else {
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
            "Модель — любой OpenAI-совместимый API; пресет OpenCode Zen\nберёт ключ на opencode.ai/auth. /new — новая сессия, /init — AGENTS.md.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ------------------------------------------------------------------- todos

@Composable
private fun TodoPanel(todos: List<dev.pocketrun.agent.opencode.Sessions.Todo>) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "План задач",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            todos.take(12).forEach { todo ->
                Row(verticalAlignment = Alignment.Top) {
                    val mark = when {
                        todo.isDone -> "✓"
                        todo.isActive -> "◐"
                        else -> "○"
                    }
                    val color = when {
                        todo.isDone -> MaterialTheme.colorScheme.onSurfaceVariant
                        todo.isActive -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(mark, color = color, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        todo.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (todo.isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------- question

@Composable
private fun QuestionCard(pq: AppViewModel.PendingQuestion) {
    val answers = remember(pq) { mutableStateOf(List(pq.questions.size) { "" }) }

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pq.questions.forEachIndexed { qi, question ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    question.header?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Text(question.question, style = MaterialTheme.typography.bodyMedium)
                    if (question.options.isNotEmpty()) {
                        Column {
                            question.options.forEach { option ->
                                val selected = answers.value[qi] == option
                                Surface(
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clickable {
                                            answers.value = answers.value.toMutableList().also { it[qi] = option }
                                        },
                                ) {
                                    Text(
                                        option,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(8.dp),
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
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = {
                        val text = if (pq.questions.size == 1) {
                            answers.value[0].ifBlank { "(пусто)" }
                        } else {
                            pq.questions.mapIndexed { i, q -> "${i + 1}. ${q.question.take(60)} → ${answers.value[i].ifBlank { "(нет ответа)" }}" }
                                .joinToString("\n")
                        }
                        pq.answer(text)
                    },
                    enabled = answers.value.any { it.isNotBlank() },
                ) { Text("Ответить") }
            }
        }
    }
}

// ---------------------------------------------------------------- sessions

@Composable
private fun SessionsDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    val sessions by viewModel.sessionList.collectAsState()
    val current by viewModel.currentSession.collectAsState()
    var renaming by remember { mutableStateOf<dev.pocketrun.agent.opencode.Sessions.SessionInfo?>(null) }
    var renameText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сессии") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { viewModel.newSession(); onDismiss() }) {
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
                                    "${s.project ?: "—"} · ${java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.updatedAt))}" +
                                        (s.parentID?.let { " · форк" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row {
                                    TextButton(onClick = { viewModel.openSession(s.id); onDismiss() }) { Text("Открыть") }
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

@Composable
private fun AgentBubble(item: AppViewModel.AgentItem) {
    when (item) {
        is AppViewModel.AgentItem.User -> BubbleRight(item.text)
        is AppViewModel.AgentItem.Assistant -> BubbleLeft(item.text)
        is AppViewModel.AgentItem.Error -> Text(
            item.text,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        is AppViewModel.AgentItem.Info -> Text(
            item.text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        is AppViewModel.AgentItem.Tool -> ToolRow(item)
    }
}

@Composable
private fun BubbleRight(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BubbleLeft(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Text(
                text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ToolRow(item: AppViewModel.AgentItem.Tool) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
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
    val catalog by viewModel.modelCatalog.collectAsState()
    val catalogState by viewModel.modelCatalogState.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки модели") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Presets: one tap fills the fields; Zen is the opencode catalog.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
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
                            if (baseUrl.contains("opencode.ai/zen")) "ключ — на opencode.ai/auth"
                            else "например https://api.openai.com/v1",
                        )
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API-ключ") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
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
                    LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
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
                Text(
                    "Ключ хранится только на устройстве и отправляется напрямую указанному серверу.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(AgentSettings.Config(baseUrl, apiKey, model)) }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
