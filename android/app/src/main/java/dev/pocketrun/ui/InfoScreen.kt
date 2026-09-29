package dev.pocketrun.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketrun.BuildConfig
import dev.pocketrun.license.LicenseManager

/** License details, runtime status, updates and build info. */
@Composable
fun InfoScreen(viewModel: AppViewModel, license: LicenseManager.LicenseState.Active) {
    val update by viewModel.updateState.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Лицензия") {
            InfoRow("Владелец", license.claims.name)
            InfoRow("Место", license.claims.seat)
            InfoRow("План", license.claims.plan)
            val days = license.daysRemaining
            InfoRow("Срок", if (days == null) "бессрочная" else "истекает через $days дн.")
            InfoRow("Ключ", if (license.isShared) "общий (в сборке)" else license.fingerprint)
        }

        UpdateCard(update, viewModel)

        SectionCard("Рантайм") {
            InfoRow("CPython", viewModel.runtimeVersion() ?: "запускается…")
            InfoRow("JavaScript", dev.pocketrun.runtime.js.JsRuntime.VERSION)
            InfoRow("npm-пакеты", "${viewModel.npxRuntime.installedPackages().size} установлено")
            InfoRow("Статус", if (viewModel.runtimeAvailable()) "готов" else "инициализация")
        }

        SectionCard("Сборка") {
            InfoRow("Версия", BuildConfig.VERSION_NAME ?: "-")
            InfoRow("versionCode", BuildConfig.VERSION_CODE.toString())
            InfoRow("Python ABI", "arm64-v8a, x86_64")
        }

        SectionCard("Агент") {
            Text(
                "Встроенный ассистент в стиле opencode: работает через любую " +
                    "OpenAI-совместимую модель (OpenAI, OpenRouter, Groq, локальный llama.cpp), " +
                    "выполняет инструменты в песочнице — файлы, Python, Node, npx.\n\n" +
                    "Сам пакет opencode-ai запустить в песочнице нельзя: у него тяжёлые нативные " +
                    "зависимости. Агент PocketRun даёт тот же сценарий работы — чат с запуском кода — " +
                    "внутри приложения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))
        if (!license.isShared) {
            Button(
                onClick = viewModel::forgetLicense,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Деактивировать лицензию")
            }
            Text(
                "После деактивации ключ нужно ввести заново; данные проектов сохранятся.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateCard(state: AppViewModel.UpdateState, viewModel: AppViewModel) {
    SectionCard("Обновления") {
        InfoRow("Установлено", viewModel.installedVersion())
        when (state) {
            is AppViewModel.UpdateState.Idle ->
                Text(
                    "Приложение проверяет GitHub-релиз раз в сутки и предлагает обновление.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            is AppViewModel.UpdateState.Checking ->
                Text("Проверяю…", style = MaterialTheme.typography.bodyMedium)

            is AppViewModel.UpdateState.UpToDate ->
                Text(
                    "Установлена последняя версия.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            is AppViewModel.UpdateState.Available -> {
                Text(
                    "Доступна ${state.release.version} (${state.release.tag}), " +
                        "${humanSize(state.release.asset.size)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (BuildConfig.DEBUG) {
                    Text(
                        "Это debug-сборка: она ставится рядом с обычной, отдельным приложением.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.release.notes.isNotEmpty()) {
                    Text(
                        state.release.notes.take(400),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::downloadUpdate) { Text("Скачать") }
                    Text(
                        "Позже",
                        modifier = Modifier
                            .clickable { viewModel.dismissUpdate() }
                            .padding(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            is AppViewModel.UpdateState.Downloading -> {
                val total = state.total
                val percent = if (total > 0) "${state.done * 100 / total}%" else "${humanSize(state.done)}"
                Text("Скачиваю… $percent", style = MaterialTheme.typography.bodyMedium)
            }

            is AppViewModel.UpdateState.Ready -> {
                Text(
                    "APK скачан (${humanSize(state.file.length())}). Установку подтверждает система.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::installUpdate) { Text("Установить") }
                    Text(
                        "Позже",
                        modifier = Modifier
                            .clickable { viewModel.dismissUpdate() }
                            .padding(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            is AppViewModel.UpdateState.Failed ->
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
        }
        if (state !is AppViewModel.UpdateState.Checking && state !is AppViewModel.UpdateState.Downloading) {
            Text(
                "Проверить сейчас",
                modifier = Modifier
                    .clickable { viewModel.checkForUpdate() }
                    .padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes <= 0L -> "размер неизвестен"
    bytes < 1024L * 1024 -> "${bytes / 1024} КБ"
    else -> String.format(java.util.Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0))
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
