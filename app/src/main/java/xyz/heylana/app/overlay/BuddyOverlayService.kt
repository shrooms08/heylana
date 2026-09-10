package xyz.heylana.app.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.heylana.app.MainActivity
import xyz.heylana.app.R
import xyz.heylana.app.brain.AnthropicClient
import xyz.heylana.app.brain.BrainReply
import xyz.heylana.app.screen.HeylanaAccessibilityService
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.voice.Listener
import xyz.heylana.app.voice.MicPermissionActivity
import xyz.heylana.app.voice.Speaker

/**
 * Foreground service that keeps the buddy on top of every other app and runs the
 * ask-about-this-screen round trip: read the screen, ask the model, speak the
 * answer, and point at the one element the answer is about.
 *
 * Declared as a `specialUse` foreground service: the app's whole purpose is an
 * always-available on-screen companion, which none of the predefined types cover.
 */
class BuddyOverlayService : Service() {

    private var overlayView: BuddyOverlayView? = null
    private var highlight: HighlightOverlayView? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inFlight: Job? = null

    private val settings: HeylanaSettings by lazy { HeylanaSettings.get(this) }
    private val brain: AnthropicClient by lazy { AnthropicClient(settings) }

    private var speaker: Speaker? = null
    private var listener: Listener? = null

    /** So the "voice unavailable" footnote is only ever shown once. */
    private var noteShown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        speaker = Speaker(this) { speaking -> overlayView?.setTalking(speaking) }
        listener = Listener(
            context = this,
            onPartial = { text -> overlayView?.showPartialSpeech(text) },
            onFinal = { text -> ask(text) },
            onProblem = { message ->
                overlayView?.stoppedListening()
                overlayView?.showNotice(message)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Heylana needs the overlay permission", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        showOverlay()
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        listener?.shutdown()
        listener = null
        speaker?.shutdown()
        speaker = null
        highlight?.removeFromWindow()
        highlight = null
        overlayView?.removeFromWindow()
        overlayView = null
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) return

        highlight = HighlightOverlayView(this).also { view ->
            view.onFadedOut = { overlayView?.stopLooking() }
            view.addToWindow()
        }

        overlayView = BuddyOverlayView(this).also { view ->
            view.onQuestion = { question -> ask(question) }
            view.onHoldStart = { startListening() }
            view.onHoldEnd = { finishListening() }
            view.onHoldCancel = { abandonListening() }
            view.onMuteToggled = { muted ->
                settings.voiceMuted = muted
                if (muted) speaker?.stop()
            }
            view.onPanelClosed = {
                speaker?.stop()
                highlight?.hide()
                view.stopLooking()
                abandonListening()
            }
            view.setMuted(settings.voiceMuted)
            view.addToWindow()
        }
    }

    // ------------------------------------------------------------------ ask

    /**
     * Reads the screen first — while the app the user is asking about is still the
     * topmost non-Heylana window — then asks the brain off the main thread.
     */
    private fun ask(question: String) {
        val view = overlayView ?: return
        if (inFlight?.isActive == true) return

        // A new question clears whatever the last one left behind.
        speaker?.stop()
        highlight?.hide()
        view.stopLooking()

        if (!HeylanaAccessibilityService.isConnected) {
            view.showNotice(
                "I can't read this screen yet. Open Heylana and switch on its accessibility " +
                    "service, then ask me again."
            )
            return
        }

        view.showThinking()
        // The keyboard squeezes the app underneath, which hides whatever sits at the
        // bottom of it — often the very button the answer is about. Drop it and let
        // the app lay itself out again before reading the screen.
        view.hideKeyboard()

        inFlight = scope.launch {
            delay(KEYBOARD_SETTLE_MS)

            val snapshot = HeylanaAccessibilityService.snapshotOrNull()
            if (snapshot == null || snapshot.isEmpty) {
                view.showNotice(
                    "I couldn't see anything on this screen. Let it finish loading and ask again."
                )
                return@launch
            }
            val screenText = snapshot.toPromptText()

            when (val reply = brain.ask(question, screenText)) {
                is BrainReply.Say -> {
                    view.showAnswer(reply.text)
                    // The snapshot is still in hand, so the id resolves to real bounds.
                    snapshot.node(reply.pointAt)?.let { node ->
                        highlight?.point(node.bounds, view.spriteCenterOnScreen())
                        view.lookAt(node.bounds.centerX())
                    }
                    speak(reply.text)
                }

                is BrainReply.Failed -> view.showNotice(reply.message)
            }
        }
    }

    private fun speak(text: String) {
        val voice = speaker ?: return
        if (settings.voiceMuted) return
        if (!voice.available) {
            if (voice.settled && !noteShown) {
                noteShown = true
                overlayView?.showNote("Voice unavailable on this device.")
            }
            return
        }
        voice.speak(text)
    }

    // ---------------------------------------------------------------- voice

    private fun startListening() {
        val view = overlayView ?: return
        val ears = listener ?: return

        if (!ears.available()) {
            view.showNotice(Listener.UNAVAILABLE)
            return
        }
        if (!micGranted()) {
            view.showNotice("Heylana needs the microphone to listen. Allow it, then hold me again.")
            requestMic()
            return
        }

        speaker?.stop()
        highlight?.hide()
        view.stopLooking()
        view.startedListening()
        ears.start()
    }

    private fun finishListening() {
        val view = overlayView ?: return
        view.stoppedListening()
        val ears = listener ?: return
        if (ears.isListening) {
            ears.stop()
        } else {
            // Nothing was captured — fall back to whatever ended up in the field.
            val typed = view.spokenText()
            if (typed.isNotEmpty()) ask(typed)
        }
    }

    private fun abandonListening() {
        listener?.cancel()
        overlayView?.stoppedListening()
    }

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** The service has no activity of its own, so it borrows a one-shot one. */
    private fun requestMic() {
        startActivity(
            Intent(this, MicPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    // --------------------------------------------------------- notification

    private fun startAsForeground() {
        createChannel()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BuddyOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Heylana is on your screen")
            .setSmallIcon(R.drawable.ic_buddy_notification)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "Stop", stop).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Heylana buddy",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while the Heylana buddy is floating on your screen"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        /** Long enough for the keyboard to finish leaving and the app to re-layout. */
        private const val KEYBOARD_SETTLE_MS = 350L

        private const val CHANNEL_ID = "heylana_buddy"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "xyz.heylana.app.action.STOP_BUDDY"

        /** Simple flag so the launcher screen can show Start vs Stop. */
        @Volatile
        var isRunning: Boolean = false

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BuddyOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BuddyOverlayService::class.java))
        }
    }
}
