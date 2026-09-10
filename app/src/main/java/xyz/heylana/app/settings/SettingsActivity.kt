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
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import xyz.heylana.app.BuildConfig
import xyz.heylana.app.ui.theme.HeylanaTheme

/**
 * Where the API key and model name live. The key is written straight into
 * [HeylanaSettings] (encrypted on device) and is never echoed back in full.
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
    var savedKeyMask by remember { mutableStateOf(settings.maskedApiKey()) }
    var replacingKey by remember { mutableStateOf(settings.maskedApiKey() == null) }
    var keyInput by remember { mutableStateOf("") }
    var quickModel by remember { mutableStateOf(settings.quickModel) }
    var taskModel by remember { mutableStateOf(settings.taskModel) }
    var status by remember { mutableStateOf("") }
    var showSpokenText by remember { mutableStateOf(settings.showTextForVoice) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Anthropic API key", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                val mask = savedKeyMask
                if (mask != null && !replacingKey) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Key saved · $mask",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(0.6f)
                        )
                        Spacer(modifier = Modifier.fillMaxWidth(0.05f))
                        OutlinedButton(onClick = {
                            replacingKey = true
                            keyInput = ""
                        }) {
                            Text(text = "Replace")
                        }
                    }
                } else {
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.fillMaxWidth(0.8f)) {
                    Text(
                        text = "Show spoken answers as text",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Off by default. When you ask by holding the buddy, " +
                            "Heylana answers out loud without showing the words.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.fillMaxWidth(0.05f))
                Switch(
                    checked = showSpokenText,
                    onCheckedChange = {
                        showSpokenText = it
                        settings.showTextForVoice = it
                    }
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Models", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = quickModel,
                    onValueChange = { quickModel = it },
                    label = { Text(text = "Quick model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Used for single questions. Cheaper. Default is " +
                        "${HeylanaSettings.DEFAULT_QUICK_MODEL}.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = taskModel,
                    onValueChange = { taskModel = it },
                    label = { Text(text = "Task model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Used for the steps of a task. Default is " +
                        "${HeylanaSettings.DEFAULT_TASK_MODEL}.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(
            onClick = {
                if (keyInput.isNotBlank()) {
                    settings.apiKey = keyInput
                    keyInput = ""
                    savedKeyMask = settings.maskedApiKey()
                    replacingKey = false
                }
                settings.quickModel = quickModel
                settings.taskModel = taskModel
                quickModel = settings.quickModel
                taskModel = settings.taskModel
                status = if (settings.hasApiKey) "Saved." else "Saved, but there is still no API key."
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Save")
        }

        if (status.isNotEmpty()) {
            Text(text = status, style = MaterialTheme.typography.bodyMedium)
        }

        if (BuildConfig.DEBUG) {
            var warmUp by remember { mutableStateOf(settings.warmUpConnection) }
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.fillMaxWidth(0.8f)) {
                        Text(
                            text = "Warm up the connection",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Debug builds only. On by default: touching the buddy " +
                                "opens the connection early so the answer arrives sooner. " +
                                "Turn it off to see the difference.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.fillMaxWidth(0.05f))
                    Switch(
                        checked = warmUp,
                        onCheckedChange = {
                            warmUp = it
                            settings.warmUpConnection = it
                        }
                    )
                }
            }

            val context = LocalContext.current
            OutlinedButton(
                onClick = {
                    // Debug builds only, so the class is named rather than imported.
                    context.startActivity(
                        Intent().setComponent(
                            ComponentName(context, DEBUG_STATES_ACTIVITY)
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Debug states")
            }
        }

        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Back")
        }
    }
}

private const val DEBUG_STATES_ACTIVITY = "xyz.heylana.app.debug.DebugStatesActivity"
