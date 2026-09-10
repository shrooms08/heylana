package xyz.heylana.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import xyz.heylana.app.overlay.BuddyOverlayService
import xyz.heylana.app.ui.theme.HeylanaTheme

class MainActivity : ComponentActivity() {

    private val overlayGranted: MutableState<Boolean> = mutableStateOf(false)
    private val buddyRunning: MutableState<Boolean> = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshStatus()
        setContent {
            HeylanaTheme {
                val notificationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { startBuddy() }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HomeScreen(
                        overlayGranted = overlayGranted.value,
                        buddyRunning = buddyRunning.value,
                        onAllowOverlay = ::openOverlaySettings,
                        onToggleBuddy = {
                            if (buddyRunning.value) {
                                stopBuddy()
                            } else if (needsNotificationPermission()) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startBuddy()
                            }
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        overlayGranted.value = Settings.canDrawOverlays(this)
        buddyRunning.value = BuddyOverlayService.isRunning
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun startBuddy() {
        BuddyOverlayService.start(this)
        buddyRunning.value = true
    }

    private fun stopBuddy() {
        BuddyOverlayService.stop(this)
        buddyRunning.value = false
    }
}

@Composable
fun HomeScreen(
    overlayGranted: Boolean,
    buddyRunning: Boolean,
    onAllowOverlay: () -> Unit,
    onToggleBuddy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Heylana", style = MaterialTheme.typography.headlineLarge)

        Text(
            text = if (overlayGranted) {
                "Overlay permission: granted"
            } else {
                "Overlay permission: not granted"
            },
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onAllowOverlay,
            enabled = !overlayGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Allow overlay")
        }

        Button(
            onClick = onToggleBuddy,
            enabled = overlayGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = if (buddyRunning) "Stop buddy" else "Start buddy")
        }
    }
}
