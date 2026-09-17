package xyz.heylana.app.actions

import android.Manifest
import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * An invisible one-shot activity for the calendar prompt, the same way the microphone
 * is asked for: the overlay service has no activity of its own, so the first reminder
 * launches this, the system asks, and it finishes straight away.
 *
 * Refused is not the end: the reminder then opens the calendar's own new-event screen
 * for the user to save by hand.
 */
class CalendarPermissionActivity : ComponentActivity() {

    private val request = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        setResult(if (granted) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request.launch(Manifest.permission.WRITE_CALENDAR)
    }
}
