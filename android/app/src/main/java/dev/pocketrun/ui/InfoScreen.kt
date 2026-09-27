package dev.pocketrun.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketrun.BuildConfig
import dev.pocketrun.license.LicenseManager

/** License details, runtime status and build info. */
@Composable
fun InfoScreen(viewModel: AppViewModel, license: LicenseManager.LicenseState.Active) {
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
            InfoRow("Отпечаток ключа", license.fingerprint)
        }

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
