package dev.pocketrun.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketrun.runtime.OutputStream
import dev.pocketrun.runtime.RuntimeKind

/** Script editor on top, live run output below. Three modes: Python, Node, npx. */
@Composable
fun EditorScreen(viewModel: AppViewModel) {
    val selected by viewModel.selected.collectAsState()
    val script by viewModel.script.collectAsState()
    val runState by viewModel.runState.collectAsState()
    val output by viewModel.output.collectAsState()
    val mode by viewModel.editorModeFlow.collectAsState()
    val npxCommand by viewModel.npxCommandFlow.collectAsState()
    val editorFile by viewModel.editorFile.collectAsState()
    val conflict by viewModel.diskConflict.collectAsState()

    // Local editor state; reset when another project, file or mode is opened.
    var text by rememberSaveable(selected?.name, mode, editorFile?.absolutePath) { mutableStateOf(script) }
    var npxText by rememberSaveable(selected?.name) { mutableStateOf(npxCommand) }

    if (selected == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Выберите или создайте проект во вкладке «Проекты».",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    editorFile?.name ?: "${selected!!.name} · ${if (mode == RuntimeKind.NODE) "main.js" else "main.py"}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (editorFile != null) {
                    Text(
                        "${selected!!.name}/${editorFile!!.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when (runState) {
                AppViewModel.RunState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("выполняется…", style = MaterialTheme.typography.bodyMedium)
                }
                is AppViewModel.RunState.Finished -> Text(
                    text = "код " + (runState as AppViewModel.RunState.Finished).exitCode +
                        " · " + (runState as AppViewModel.RunState.Finished).durationMs + " мс",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if ((runState as AppViewModel.RunState.Finished).exitCode == 0) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                is AppViewModel.RunState.Failed -> Text(
                    (runState as AppViewModel.RunState.Failed).message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                AppViewModel.RunState.Idle -> {}
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == RuntimeKind.PYTHON,
                onClick = { viewModel.setEditorMode(RuntimeKind.PYTHON) },
                label = { Text("Python") },
            )
            FilterChip(
                selected = mode == RuntimeKind.NODE,
                onClick = { viewModel.setEditorMode(RuntimeKind.NODE) },
                label = { Text("Node.js") },
            )
            FilterChip(
                selected = mode == RuntimeKind.NPX,
                onClick = { viewModel.setEditorMode(RuntimeKind.NPX) },
                label = { Text("npx") },
            )
        }
        Spacer(Modifier.height(8.dp))

        if (conflict != null) {
            // The agent edited the file this editor holds. Overwriting it silently
            // would throw away the agent's work; reloading would throw away the
            // user's typing, so the choice is theirs.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Файл изменился на диске — это мог сделать агент.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::reloadFromDisk) { Text("Загрузить") }
                TextButton(
                    onClick = {
                        // Saving is the user saying "my text is the one that counts".
                        viewModel.saveScript(text)
                    },
                ) { Text("Оставить мой") }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (mode == RuntimeKind.NPX) {
            Column(Modifier.fillMaxWidth().weight(1.2f)) {
                Text(
                    "Запуск npm-пакета без установки окружения: чистые JS-пакеты скачиваются из registry.npmjs.org и выполняются в песочнице. Нативные модули и postinstall-скрипты не поддерживаются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = npxText,
                    onValueChange = {
                        npxText = it
                        viewModel.npxCommand.value = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("cowsay привет") },
                    supportingText = { Text("пакет[@версия] [аргументы] — например: cowsay Привет! · semver -h · happy-birthday@1") },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Установленные пакеты хранятся в песочнице (packages/) и переиспользуются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1.2f),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = {
                    Text(
                        if (mode == RuntimeKind.NODE) "console.log('Привет!')" else "print('Привет!')",
                    )
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    if (mode == RuntimeKind.NPX) viewModel.runNpxCommand(npxText) else viewModel.runScript(text)
                },
                enabled = runState !is AppViewModel.RunState.Running,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (mode == RuntimeKind.NPX) "Запустить" else "Сохранить и запустить")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = viewModel::cancelRun,
                enabled = runState is AppViewModel.RunState.Running,
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Стоп")
            }
            Spacer(Modifier.width(8.dp))
            if (output.isNotEmpty()) {
                TextButton(onClick = viewModel::clearOutput) { Text("Очистить") }
            }
        }
        Spacer(Modifier.height(8.dp))

        Text("Вывод", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutputConsole(output, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun OutputConsole(output: List<AppViewModel.OutputLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // Keep the newest line in view as it streams in.
    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) listState.animateScrollToItem(output.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (output.isEmpty()) {
            item {
                Text(
                    "— пусто —",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(output) { _, line ->
            Text(
                text = line.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (line.stream == OutputStream.STDERR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
