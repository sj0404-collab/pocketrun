package dev.pocketrun

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dev.pocketrun.agent.TurnNotifier
import dev.pocketrun.ui.PocketRunApp

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TurnNotifier.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            PocketRunApp()
        }
    }

    override fun onResume() {
        super.onResume()
        TurnNotifier.setForeground(true)
    }

    override fun onStop() {
        TurnNotifier.setForeground(false)
        super.onStop()
    }
}
