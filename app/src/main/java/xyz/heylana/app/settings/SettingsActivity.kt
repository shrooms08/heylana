package xyz.heylana.app.settings

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.ui.theme.HeylanaTheme

/**
 * The settings screen.
 *
 * Nothing here is needed to use Heylana any more: the keys live in the proxy.
 * What is left is the voice, what leaves the phone, and an advanced drawer for
 * people who want to point the app somewhere else or pay for their own answers.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = HeylanaSettings.get(this)
        setContent {
            HeylanaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SettingsScreen(
                        settings = settings,
                        onDone = { finish() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: HeylanaSettings,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSpokenText by remember { mutableStateOf(settings.showTextForVoice) }
    var advanced by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)

        SwitchCard(
            title = "Show spoken answers as text",
            detail = "Off by default. When you ask by holding the buddy, Heylana answers " +
                "out loud without showing the words.",
            checked = showSpokenText,
            onCheckedChange = {
                showSpokenText = it
                settings.showTextForVoice = it
            }
        )

        PrivacyCard()

        OutlinedButton(
            onClick = { advanced = !advanced },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = if (advanced) "Hide advanced" else "Advanced")
        }

        if (advanced) {
            AdvancedSection(settings = settings)
        }

        if (BuildConfig.DEBUG) {
            DebugSection(settings = settings)
        }

        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Back")
        }
    }
}

/** The plain-words version of what goes where. Same sentences as onboarding. */
@Composable
private fun PrivacyCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "What leaves the phone", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Heylana reads the screen only when you ask, and watches for your " +
                    "tap only while it is pointing at something.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your voice goes to Deepgram to be transcribed while you hold the " +
                    "buddy. The spoken answer text goes to Cartesia to become speech. The " +
                    "screen never goes to either.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Two things almost nobody needs: sending the questions somewhere else, and
 * paying for them yourself.
 */
@Composable
private fun AdvancedSection(settings: HeylanaSettings) {
    var proxyUrl by remember { mutableStateOf(settings.proxyUrlOverride) }
    var useOwnKey by remember { mutableStateOf(settings.useOwnKey) }
    var savedKeyMask by remember { mutableStateOf(settings.maskedApiKey()) }
    var keyInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Where Heylana asks", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Built in: ${BuildConfig.PROXY_URL.ifEmpty { "not set" }}",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = proxyUrl,
                onValueChange = { proxyUrl = it },
                label = { Text(text = "Use this address instead") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Leave empty to use the built-in one.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.fillMaxWidth(0.8f)) {
                    Text(text = "Use my own key", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Off by default. On, the questions go straight to Anthropic " +
                            "on your own key and you pay for them. The voice and the ears " +
                            "still go through Heylana.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.fillMaxWidth(0.05f))
                Switch(
                    checked = useOwnKey,
                    onCheckedChange = {
                        useOwnKey = it
                        settings.useOwnKey = it
                    }
                )
            }

            if (useOwnKey || savedKeyMask != null) {
                Spacer(modifier = Modifier.height(12.dp))
                val mask = savedKeyMask
                if (mask != null && keyInput.isEmpty()) {
                    Text(
                        text = "Key saved · $mask",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(text = "Paste your key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Stored encrypted on this phone only.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    Button(
        onClick = {
            settings.proxyUrlOverride = proxyUrl
            proxyUrl = settings.proxyUrlOverride
            if (keyInput.isNotBlank()) {
                settings.apiKey = keyInput
                keyInput = ""
                savedKeyMask = settings.maskedApiKey()
            }
            settings.useOwnKey = useOwnKey
            useOwnKey = settings.useOwnKey
            status = if (useOwnKey && !settings.hasApiKey) {
                "Saved, but there is still no key to use."
            } else {
                "Saved."
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "Save")
    }

    if (status.isNotEmpty()) {
        Text(text = status, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Debug builds only: things that exist to make a fallback happen on purpose. */
@Composable
private fun DebugSection(settings: HeylanaSettings) {
    var warmUp by remember { mutableStateOf(settings.warmUpConnection) }
    val context = LocalContext.current

    SwitchCard(
        title = "Warm up the connection",
        detail = "Debug builds only. On by default: touching the buddy opens the " +
            "connection early so the answer arrives sooner. Turn it off to see the " +
            "difference.",
        checked = warmUp,
        onCheckedChange = {
            warmUp = it
            settings.warmUpConnection = it
        }
    )

    OutlinedButton(
        onClick = {
            // Debug builds only, so the class is named rather than imported.
            context.startActivity(
                Intent().setComponent(ComponentName(context, DEBUG_STATES_ACTIVITY))
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = "Debug states")
    }
}

@Composable
private fun SwitchCard(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.8f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = detail, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.fillMaxWidth(0.05f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private const val DEBUG_STATES_ACTIVITY = "xyz.heylana.app.debug.DebugStatesActivity"
