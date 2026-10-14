package dev.pocketrun.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocketrun.license.LicenseManager

/** Root: activation gate first, then the four-tab main screen. */
@Composable
fun PocketRunApp(viewModel: AppViewModel = viewModel()) {
    val license by viewModel.licenseState.collectAsState()
    val licensed = license is LicenseManager.LicenseState.Active
    LaunchedEffect(licensed) {
        if (licensed) viewModel.checkUpdateOnStart()
    }
    PocketRunTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (val state = license) {
                is LicenseManager.LicenseState.Active -> MainScreen(viewModel, state)
                else -> ActivationScreen(viewModel, license)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(viewModel: AppViewModel, license: LicenseManager.LicenseState.Active) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Opening a file from the tree asks for this tab; the tab bar does not know
    // about the file manager, so the request travels through the model.
    val tabRequest by viewModel.tabRequest.collectAsState()
    LaunchedEffect(tabRequest) {
        val requested = tabRequest
        if (requested != null) {
            tab = requested
            viewModel.tabRequestHandled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("PocketRun") })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    label = { Text("Проекты") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Code, contentDescription = null) },
                    label = { Text("Редактор") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.SmartToy, contentDescription = null) },
                    label = { Text("Агент") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    label = { Text("Инфо") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ProjectsScreen(viewModel)
                1 -> EditorScreen(viewModel)
                2 -> AgentScreen(viewModel)
                else -> InfoScreen(viewModel, license)
            }
        }
    }
}
