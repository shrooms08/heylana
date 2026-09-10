package xyz.heylana.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import xyz.heylana.app.overlay.BuddyOverlayService
import xyz.heylana.app.screen.HeylanaAccessibilityService
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.settings.SettingsActivity
import xyz.heylana.app.ui.theme.HeylanaTheme

/**
 * The four things Heylana needs before it can answer anything, plus the
 * microphone — which is optional: without it typing still works.
 */
private data class Checklist(
    val overlay: Boolean = false,
    val accessibility: Boolean = false,
    val notifications: Boolean = false,
    val apiKey: Boolean = false,
    val microphone: Boolean = false
) {
    val allDone: Boolean get() = overlay && accessibility && notifications && apiKey
}

class MainActivity : ComponentActivity() {

    private val checklist: MutableState<Checklist> = mutableStateOf(Checklist())
    private val buddyRunning: MutableState<Boolean> = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshStatus()
        setContent {
            HeylanaTheme {
                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { refreshStatus() }
                val microphoneLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { refreshStatus() }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SetupScreen(
                        checklist = checklist.value,
                        buddyRunning = buddyRunning.value,
                        onAllowOverlay = ::openOverlaySettings,
                        onOpenAccessibility = ::openAccessibilitySettings,
                        onAllowNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onAllowMicrophone = {
                            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onOpenSettings = ::openHeylanaSettings,
                        onToggleBuddy = {
                            if (buddyRunning.value) stopBuddy() else startBuddy()
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
        checklist.value = Checklist(
            overlay = Settings.canDrawOverlays(this),
            accessibility = HeylanaAccessibilityService.isEnabled(this),
            notifications = notificationsAllowed(),
            apiKey = HeylanaSettings.get(this).hasApiKey,
            microphone = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
        buddyRunning.value = BuddyOverlayService.isRunning
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openHeylanaSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

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
private fun SetupScreen(
    checklist: Checklist,
    buddyRunning: Boolean,
    onAllowOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onAllowNotifications: () -> Unit,
    onAllowMicrophone: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleBuddy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Heylana", style = MaterialTheme.typography.headlineLarge)
        Text(
            text = "Four things to switch on, then your buddy can answer questions " +
                "about whatever app is on your screen. The microphone is optional — " +
                "it only lets you talk to the buddy instead of typing.\n\n" +
                "Heylana reads the screen only when you ask, and watches for your tap " +
                "only while it is pointing at something.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(4.dp))

        ChecklistRow(
            title = "Overlay permission",
            done = checklist.overlay,
            actionLabel = "Allow overlay",
            onAction = onAllowOverlay
        )

        ChecklistRow(
            title = "Screen reading (accessibility)",
            done = checklist.accessibility,
            actionLabel = "Open accessibility settings",
            onAction = onOpenAccessibility,
            instructions = listOf(
                "1. Find Heylana under Downloaded apps or Installed apps.",
                "2. Tap Heylana and turn the switch on.",
                "3. Confirm the dialog, then come back here."
            )
        )

        ChecklistRow(
            title = "Notifications",
            done = checklist.notifications,
            actionLabel = "Allow notifications",
            onAction = onAllowNotifications
        )

        ChecklistRow(
            title = "API key",
            done = checklist.apiKey,
            actionLabel = "Add API key",
            onAction = onOpenSettings
        )

        ChecklistRow(
            title = "Microphone (optional)",
            done = checklist.microphone,
            actionLabel = "Allow microphone",
            onAction = onAllowMicrophone,
            instructions = listOf(
                "Only needed to talk to Heylana by holding the buddy.",
                "Typing works without it."
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onToggleBuddy,
            enabled = checklist.allDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = if (buddyRunning) "Stop buddy" else "Start buddy")
        }

        if (!checklist.allDone) {
            Text(
                text = "Finish the first four rows above to start the buddy.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Settings")
        }
    }
}

@Composable
private fun ChecklistRow(
    title: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    instructions: List<String> = emptyList()
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (done) "✓" else "•",
                    color = if (done) DONE_GREEN else MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!done) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(text = actionLabel)
                }
                for (line in instructions) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private val DONE_GREEN = Color(0xFF15803D)
