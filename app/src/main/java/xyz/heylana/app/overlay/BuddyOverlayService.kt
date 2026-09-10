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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import xyz.heylana.app.brain.Conversation
import xyz.heylana.app.brain.GuidanceSession
import xyz.heylana.app.screen.HeylanaAccessibilityService
import xyz.heylana.app.screen.ScreenSnapshot
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

    /** The task being walked through right now, if any. */
    private var session: GuidanceSession? = null
    private val conversation = Conversation()

    private val main = Handler(Looper.getMainLooper())
    private val autoAdvanceCheck = Runnable { considerAutoAdvance() }

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
        endSession(clearBox = false)
        main.removeCallbacksAndMessages(null)
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
            view.onNext = { advance(userAsked = true) }
            view.onDone = { endSession(clearBox = true) }
            view.onPanelClosed = {
                speaker?.stop()
                highlight?.hide()
                view.stopLooking()
                abandonListening()
                endSession(clearBox = true)
                conversation.clear()
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

        // A new question drops whatever the last one left behind, including any
        // task that was running.
        speaker?.stop()
        highlight?.hide()
        view.stopLooking()
        endSession(clearBox = false)

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

            val snapshot = readScreen(view) ?: return@launch

            when (val reply = brain.ask(question, snapshot.toPromptText(), conversation.asPromptText())) {
                is BrainReply.Say -> {
                    val task = reply.task
                    if (task != null && !task.done) {
                        startSession(task.goal, reply, snapshot)
                    } else {
                        conversation.record(question, reply.text)
                        showOneShot(reply, snapshot)
                    }
                }

                is BrainReply.Failed -> view.showNotice(reply.message)
            }
        }
    }

    /** An ordinary answer: say it, point once, let the box time out by itself. */
    private fun showOneShot(reply: BrainReply.Say, snapshot: ScreenSnapshot) {
        val view = overlayView ?: return
        view.showAnswer(reply.text)
        // The snapshot is still in hand, so the id resolves to real bounds.
        snapshot.node(reply.pointAt)?.let { node ->
            view.avoidOverlap(node.bounds)
            highlight?.point(node.bounds, view.spriteCenterOnScreen())
            view.lookAt(node.bounds.centerX())
        }
        speak(reply.text)
    }

    private fun readScreen(view: BuddyOverlayView): ScreenSnapshot? {
        val snapshot = HeylanaAccessibilityService.snapshotOrNull()
        if (snapshot == null || snapshot.isEmpty) {
            view.showNotice(
                "I couldn't see anything on this screen. Let it finish loading and ask again."
            )
            return null
        }
        return snapshot
    }

    // -------------------------------------------------------------- guidance

    private fun startSession(goal: String, reply: BrainReply.Say, snapshot: ScreenSnapshot) {
        session = GuidanceSession(goal)
        conversation.clear()
        // Screen-change events are switched on here and nowhere else.
        HeylanaAccessibilityService.watchScreenChanges { main.post { onScreenChanged() } }
        showStep(reply, snapshot)
    }

    /** Draws, speaks and records one step of the running task. */
    private fun showStep(reply: BrainReply.Say, snapshot: ScreenSnapshot) {
        val view = overlayView ?: return
        val current = session ?: return

        val node = snapshot.node(reply.pointAt)
        val repeated = current.record(
            say = reply.text,
            elementKey = node?.key,
            elementLabel = node?.label,
            packageName = snapshot.packageName
        )

        val spoken = if (repeated) STUCK_LINE else reply.text

        view.ensurePanelOpen()
        // Text and chip first, so the card is its final size before it is moved
        // clear of whatever is about to be boxed.
        view.showAnswer(spoken)
        view.showSession(current.stepNumber)

        if (node != null) {
            view.avoidOverlap(node.bounds)
            // Stays up until the step changes: the user needs it while they look.
            highlight?.point(node.bounds, view.spriteCenterOnScreen(), persistent = true)
            view.lookAt(node.bounds.centerX())
        } else {
            // No box for this step, but the task is still running.
            highlight?.hide()
            view.stopLooking()
        }

        speak(spoken)
    }

    /**
     * Moves the task on: reads the screen again and asks for the next step.
     * [userAsked] means they tapped Next, which also clears a stuck session.
     */
    private fun advance(userAsked: Boolean) {
        val view = overlayView ?: return
        val current = session ?: return
        if (inFlight?.isActive == true) return
        if (userAsked) current.unstick()

        main.removeCallbacks(autoAdvanceCheck)

        if (current.atCap) {
            val line = "That is as far as I can take you, so let's stop here."
            view.showAnswer(line)
            speak(line)
            endSession(clearBox = true)
            return
        }

        speaker?.stop()
        view.showThinking()
        view.hideKeyboard()

        inFlight = scope.launch {
            delay(KEYBOARD_SETTLE_MS)
            val snapshot = readScreen(view) ?: return@launch

            val reply = brain.nextStep(
                goal = current.goal,
                historyText = current.historyText(),
                screenText = snapshot.toPromptText(),
                stepNumber = current.stepNumber + 1
            )

            when (reply) {
                is BrainReply.Say -> {
                    if (reply.task?.done == true) {
                        view.showAnswer(reply.text)
                        speak(reply.text)
                        endSession(clearBox = true)
                    } else {
                        showStep(reply, snapshot)
                    }
                }

                // Any API trouble ends the task cleanly, with the reason on screen.
                is BrainReply.Failed -> {
                    view.showNotice(reply.message)
                    endSession(clearBox = true)
                }
            }
        }
    }

    /** Called only while a session is live — see the accessibility service. */
    private fun onScreenChanged() {
        val current = session ?: return
        if (current.stuck) return
        if (inFlight?.isActive == true) return
        main.removeCallbacks(autoAdvanceCheck)
        main.postDelayed(autoAdvanceCheck, AUTO_ADVANCE_DEBOUNCE_MS)
    }

    /**
     * The step is finished when the thing we pointed at has gone, or the user has
     * moved to a different app.
     */
    private fun considerAutoAdvance() {
        val current = session ?: return
        if (current.stuck) return
        if (inFlight?.isActive == true) return

        val snapshot = HeylanaAccessibilityService.snapshotOrNull() ?: return
        if (snapshot.isEmpty) return

        val movedApp = current.pointedPackage != null &&
            snapshot.packageName != current.pointedPackage
        val pointedGone = current.pointedKey?.let { !snapshot.contains(it) } ?: false

        if (movedApp || pointedGone) advance(userAsked = false)
    }

    private fun endSession(clearBox: Boolean) {
        main.removeCallbacks(autoAdvanceCheck)
        if (session == null) return
        session = null
        // Events go straight back off: no session, no listening.
        HeylanaAccessibilityService.watchScreenChanges(null)
        overlayView?.hideSession()
        if (clearBox) {
            highlight?.hide()
            overlayView?.stopLooking()
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

        /** Lets a screen settle after a tap before deciding the step is finished. */
        private const val AUTO_ADVANCE_DEBOUNCE_MS = 900L

        private const val STUCK_LINE =
            "Looks like that didn't work. Try tapping it again, or tell me what you see."

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
