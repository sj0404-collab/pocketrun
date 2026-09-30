package dev.pocketrun.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketrun.core.FileKind
import dev.pocketrun.core.FileNode
import dev.pocketrun.core.FileText

/**
 * Projects and the files inside them.
 *
 * A project is a folder, so the screen shows the folder: the tree, the filters
 * and everything the file manager can do with what is in there. The list updates
 * itself - the agent writes files from another thread and the tree follows.
 */
@Composable
fun ProjectsScreen(viewModel: AppViewModel) {
    val projects by viewModel.projects.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val tree by viewModel.tree.collectAsState()
    val expanded by viewModel.expandedDirs.collectAsState()
    val filter by viewModel.treeFilter.collectAsState()
    val inspected by viewModel.inspected.collectAsState()
    val hits by viewModel.searchHits.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val historyFor by viewModel.historyFor.collectAsState()
    val versions by viewModel.versions.collectAsState()

    var newProject by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<EntryDialog?>(null) }
    var contentQuery by remember { mutableStateOf("") }
    var contentMode by remember { mutableStateOf(false) }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.importFromPhone(uris, inspected?.takeIf { it?.isDirectory == true })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Проекты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.refreshFiles() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
            }
        }

        if (projects.isEmpty()) {
            Text(
                "Создайте первый проект — это обычная папка с файлами.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(projects, key = { it.dir.absolutePath }) { project ->
                    val isSelected = selected?.dir == project.dir
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.select(project) },
                        label = { Text(project.name) },
                        trailingIcon = if (isSelected) {
                            {
                                IconButton(
                                    modifier = Modifier.size(20.dp),
                                    onClick = { viewModel.deleteProject(project) },
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Удалить проект",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newProject,
                onValueChange = { newProject = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("новый проект") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.createProject(newProject)
                    newProject = ""
                },
                enabled = newProject.isNotBlank(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Создать")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        if (selected == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Выберите проект — увидите все его файлы.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        FileToolbar(
            filter = filter,
            notice = notice,
            onQuery = viewModel::setTreeQuery,
            onKind = viewModel::setTreeKind,
            onHidden = viewModel::toggleHiddenFiles,
            onClearFilter = viewModel::clearTreeFilter,
            onNewFile = { dialog = EntryDialog.Create(inspected?.takeIf { it?.isDirectory == true }) },
            onNewFolder = { dialog = EntryDialog.CreateFolder(inspected?.takeIf { it?.isDirectory == true }) },
            onImport = { importPicker.launch(arrayOf("*/*")) },
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            items(TREE_FILTERS) { (kind, label) ->
                FilterChip(
                    selected = filter.kind == kind,
                    onClick = { viewModel.setTreeKind(kind) },
                    label = { Text(label, fontSize = 12.sp) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = contentQuery,
                onValueChange = { contentQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("поиск по содержимому", fontSize = 13.sp) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (contentQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            contentQuery = ""
                            contentMode = false
                            viewModel.clearSearch()
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions.Default,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = {
                    contentMode = true
                    viewModel.searchContent(contentQuery)
                },
                enabled = contentQuery.isNotBlank() && !searching,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                Text(if (searching) "…" else "Найти", fontSize = 13.sp)
            }
        }

        if (contentMode && hits.isNotEmpty()) {
            SearchResults(hits, onOpen = { file ->
                contentMode = false
                viewModel.openFile(file)
            })
        } else if (contentMode && !searching && hits.isEmpty() && contentQuery.isNotBlank()) {
            Text(
                "Совпадений нет",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(tree, key = { it.file.absolutePath }) { node ->
                TreeRow(
                    node = node,
                    expanded = node.file.absolutePath in expanded,
                    onClick = { viewModel.activate(node.file) },
                    onLongPress = { viewModel.inspect(node.file) },
                )
            }
            if (tree.isEmpty()) {
                item {
                    Text(
                        if (filter.isFiltering) "Ничего не найдено" else "Папка пуста — создайте файл или импортируйте с телефона",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }

        if (inspected != null) {
            FileActionBar(
                file = inspected!!,
                expanded = inspected!!.absolutePath in expanded,
                onOpen = { viewModel.openFile(inspected!!) },
                onRename = { dialog = EntryDialog.Rename(inspected!!) },
                onDelete = { dialog = EntryDialog.Delete(inspected!!) },
                onShare = { viewModel.shareFile(inspected!!) },
                onExternal = { viewModel.openExternally(inspected!!) },
                onHistory = { viewModel.showHistory(inspected!!) },
                onCopy = { dialog = EntryDialog.Create(inspected!!.takeIf { it.isDirectory }) },
            )
        }
    }

    dialog?.let { current ->
        when (current) {
            is EntryDialog.Create -> NameDialog(
                title = "Новый файл",
                label = "имя файла",
                initial = "",
                onDismiss = { dialog = null },
                onConfirm = { name ->
                    viewModel.createFile(name, current.into)
                    dialog = null
                },
            )
            is EntryDialog.CreateFolder -> NameDialog(
                title = "Новая папка",
                label = "имя папки",
                initial = "",
                onDismiss = { dialog = null },
                onConfirm = { name ->
                    viewModel.createFolder(name, current.into)
                    dialog = null
                },
            )
            is EntryDialog.Rename -> NameDialog(
                title = "Переименовать",
                label = "новое имя",
                initial = current.file.name,
                onDismiss = { dialog = null },
                onConfirm = { name ->
                    viewModel.renameEntry(current.file, name)
                    dialog = null
                },
            )
            is EntryDialog.Delete -> ConfirmDialog(
                title = "Удалить?",
                text = if (current.file.isDirectory) {
                    "Папка «${current.file.name}» и всё её содержимое. Для файлов внутри сохранится копия в истории."
                } else {
                    "Файл «${current.file.name}». Перед удалением сохранится копия в истории."
                },
                onDismiss = { dialog = null },
                onConfirm = {
                    viewModel.deleteEntry(current.file)
                    dialog = null
                },
            )
        }
    }

    if (historyFor != null) {
        AlertDialog(
            onDismissRequest = viewModel::closeHistory,
            title = { Text("История: ${historyFor!!.name}") },
            text = {
                if (versions.isEmpty()) {
                    Text("Пока пусто. Копия снимается сама перед каждой перезаписью — и её можно снять вручную кнопкой «Версия».")
                } else {
                    Column {
                        Text(
                            "${versions.size} сохранённых копий",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        versions.take(30).forEach { version ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stampOf(version.stamp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        FileText.human(version.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { viewModel.restoreVersion(version) }) {
                                    Text("Вернуть")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeHistory) { Text("Закрыть") } },
        )
    }

    if (preview !is AppViewModel.FilePreview.None) {
        FileViewerDialog(preview = preview, viewModel = viewModel)
    }
}

/** The filter chips: one kind at a time, or everything. */
private val TREE_FILTERS: List<Pair<FileKind?, String>> = listOf(
    null to "Все",
    FileKind.CODE to "Код",
    FileKind.TEXT to "Текст",
    FileKind.IMAGE to "Фото",
    FileKind.AUDIO to "Аудио",
    FileKind.VIDEO to "Видео",
    FileKind.ARCHIVE to "Архив",
    FileKind.OTHER to "Прочее",
)

@Composable
private fun FileToolbar(
    filter: dev.pocketrun.core.TreeFilter,
    notice: String?,
    onQuery: (String) -> Unit,
    onKind: (FileKind?) -> Unit,
    onHidden: () -> Unit,
    onClearFilter: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onImport: () -> Unit,
) {
    var addOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f),
            placeholder = { Text("поиск по имени", fontSize = 13.sp) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (filter.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            },
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(6.dp))
        Box {
            IconButton(onClick = { addOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Создать")
            }
            DropdownMenu(addOpen, onDismissRequest = { addOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Новый файл") },
                    onClick = { addOpen = false; onNewFile() },
                )
                DropdownMenuItem(
                    text = { Text("Новая папка") },
                    onClick = { addOpen = false; onNewFolder() },
                )
            }
        }
        IconButton(onClick = onImport) {
            Icon(Icons.Filled.Description, contentDescription = "Импорт с телефона")
        }
        IconButton(onClick = onHidden) {
            Icon(
                Icons.Filled.Code,
                contentDescription = "Скрытые файлы",
                tint = if (filter.showHidden) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    if (filter.isFiltering) {
        AssistChip(
            onClick = onClearFilter,
            label = { Text("сбросить фильтр", fontSize = 12.sp) },
            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp)) },
        )
    }
    notice?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(node: FileNode, expanded: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(start = (4 + node.depth * 14).dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(iconFor(node.kind), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                node.isFolder -> "${node.folders} папок · ${node.files} файлов"
                else -> FileText.human(node.size)
            }
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (node.isFolder) {
            Icon(
                if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun FileActionBar(
    file: java.io.File,
    expanded: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onExternal: () -> Unit,
    onHistory: () -> Unit,
    onCopy: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            file.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onOpen, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
                Text(
                    when {
                        !file.isDirectory -> "Открыть"
                        expanded -> "Свернуть папку"
                        else -> "Открыть папку"
                    },
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = onHistory, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
                Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Версия", fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "Поделиться")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Ещё")
                }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (file.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("Создать файл здесь") },
                            onClick = { menuOpen = false; onCopy() },
                        )
                    }
                    if (!file.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("Открыть системным приложением") },
                            onClick = { menuOpen = false; onExternal() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Переименовать") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResults(hits: List<dev.pocketrun.core.SearchHit>, onOpen: (java.io.File) -> Unit) {
    Column(Modifier.fillMaxWidth().height(160.dp)) {
        Text(
            "Совпадений: ${hits.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f)) {
            items(hits.take(100), key = { "${it.path}:${it.line}" }) { hit ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(hit.file) }
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        "${hit.file.name}:${hit.line}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(120.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        hit.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ок")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun iconFor(kind: FileKind): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Filled.Folder
    FileKind.CODE -> Icons.Filled.Code
    FileKind.TEXT -> Icons.Filled.Description
    FileKind.IMAGE -> Icons.Filled.Image
    FileKind.AUDIO -> Icons.Filled.MusicNote
    FileKind.VIDEO -> Icons.Filled.Movie
    FileKind.ARCHIVE, FileKind.OTHER -> Icons.Filled.InsertDriveFile
}

private fun stampOf(stamp: Long): String {
    val format = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault())
    return format.format(java.util.Date(stamp))
}

private sealed interface EntryDialog {
    data class Create(val into: java.io.File?) : EntryDialog
    data class CreateFolder(val into: java.io.File?) : EntryDialog
    data class Rename(val file: java.io.File) : EntryDialog
    data class Delete(val file: java.io.File) : EntryDialog
}
