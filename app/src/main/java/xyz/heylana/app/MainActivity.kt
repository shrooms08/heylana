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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import xyz.heylana.app.overlay.BuddyOverlayService
import xyz.heylana.app.screen.HeylanaAccessibilityService
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.settings.VoiceCopy
import xyz.heylana.app.settings.SettingsActivity
import xyz.heylana.app.ui.theme.HeylanaTheme
import xyz.heylana.app.home.HeylanaApp
import xyz.heylana.app.wallet.SeedVault

/**
 * The four things Heylana needs before it can answer anything, plus the
 * microphone — which is optional: without it typing still works.
 */
private data class Checklist(
    val overlay: Boolean = false,
    val accessibility: Boolean = false,
    val notifications: Boolean = false,

    val microphone: Boolean = false
) {
    val allDone: Boolean get() = overlay && accessibility && notifications
}

class MainActivity : ComponentActivity() {

    /** Created with the activity, as Mobile Wallet Adapter requires. */
    private lateinit var seedVault: SeedVault

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        seedVault = SeedVault(this)
        // First run (sign in, name, permissions) or, for a returning user, Home.
        // Debug builds only: `-e screen home|sign_in|permissions|voice|skills|advanced|privacy|settings`
        // opens that screen, so each can be looked at without wiping the app's data.
        val forced = if (BuildConfig.DEBUG) intent.getStringExtra("screen") else null
        setContent { HeylanaApp(this, seedVault, forced) }
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
            text = "Three things to switch on, then your buddy can answer questions " +
                "about whatever app is on your screen. The microphone is optional — " +
                "it only lets you talk to the buddy instead of typing.\n\n" +
                "Heylana reads the screen only when you ask, and watches for your tap " +
                "only while it is pointing at something.\n\n" +
                VoiceCopy.privacyLine(HeylanaSettings.get(LocalContext.current).voiceProvider),
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
                "3. Confirm the dialog, then come back here.",
                "Already on but still not ticked? Turn it off and on again."
            )
        )

        ChecklistRow(
            title = "Notifications",
            done = checklist.notifications,
            actionLabel = "Allow notifications",
            onAction = onAllowNotifications
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
                text = "Finish the first three rows above to start the buddy.",
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
