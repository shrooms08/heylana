package xyz.heylana.app.voice

import android.Manifest
import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * An invisible one-shot activity whose only job is to show the microphone prompt.
 *
 * The overlay service has no activity of its own, so the first time the user holds
 * the buddy it launches this, the system asks, and this finishes straight away.
 */
class MicPermissionActivity : ComponentActivity() {

    private val request = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        setResult(if (granted) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request.launch(Manifest.permission.RECORD_AUDIO)
    }
}
