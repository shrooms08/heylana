package xyz.heylana.app.actions

import android.Manifest
import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * An invisible one-shot activity for the contacts prompt, the same way the microphone
 * and the calendar are asked for. Refused is not the end: a text to a name then opens
 * Messages with the words written, for the user to pick the person.
 */
class ContactsPermissionActivity : ComponentActivity() {

    private val request = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        setResult(if (granted) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request.launch(Manifest.permission.READ_CONTACTS)
    }
}
