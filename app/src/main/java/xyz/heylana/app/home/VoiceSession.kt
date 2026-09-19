package xyz.heylana.app.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.net.Proxy
import xyz.heylana.app.screen.Keyterms
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.voice.DeepgramEars
import xyz.heylana.app.voice.EarCallbacks
import xyz.heylana.app.voice.EarsRace
import xyz.heylana.app.voice.Listener

/**
 * The app's ears: the same two the buddy uses, raced the same way ([EarsRace]), feeding
 * [AppChat]. The phone's recogniser and Deepgram both start when listening starts; when
 * it finishes, the race picks whose words go, and they are sent as an ordinary in-app
 * message — chat, no screen read. Nothing heard is said as such, never sent.
 */
class VoiceSession(
    private val context: Context,
    private val settings: HeylanaSettings,
    private val scope: CoroutineScope,
    private val chat: AppChat
) {
    enum class Phase {
        /** Not listening; tap or hold the mic. */
        READY,
        LISTENING,
        /** Let go: waiting for the ears' last words. */
        WAITING,
        /** Paused by the user; the mic picks up again. */
        PAUSED
    }

    var phase by mutableStateOf(Phase.READY)
        private set

    /** The microphone's level, 0 to 1, while listening. */
    var level by mutableFloatStateOf(0f)
        private set

    /** The words so far, shown under the timer while they are being said. */
    var heard by mutableStateOf("")
        private set

    /** A line of our own: nothing heard, no microphone. */
    var note by mutableStateOf<String?>(null)
        private set

    /** When the current listen started, and how long it ran, for the timer. */
    var startedAt by mutableLongStateOf(0L)
        private set
    var ranMs by mutableLongStateOf(0L)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var race: EarsRace? = null
    private var cloud: DeepgramEars? = null
    private var assembly: xyz.heylana.app.voice.AssemblyEars? = null
    private var deepgramSpoke = false
    private var assemblySpoke = false
    private var releasedAt = 0L

    private val phone: Listener by lazy { Listener(context, callbacksFor(EarsRace.Ear.ANDROID)) }

    private val raceTick = Runnable { judge(race?.tick(SystemClock.uptimeMillis())) }
    /** A mic left open is not a question: at the limit, listening stops and nothing is sent. */
    private val limit = Runnable {
        if (phase == Phase.LISTENING) {
            HeylanaLog.state("app: voice listened for the limit, stopped without sending")
            pause()
            note = TOO_LONG
        }
    }

    fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Starts both ears. False if the microphone is not allowed (the caller asks for it). */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (phase == Phase.LISTENING || phase == Phase.WAITING) return true
        if (!micGranted()) {
            HeylanaLog.state("app: voice needs the microphone")
            return false
        }
        chat.silence()
        note = null
        heard = ""
        level = 0f
        deepgramSpoke = false
        assemblySpoke = false
        startedAt = SystemClock.uptimeMillis()
        ranMs = 0L
        phase = Phase.LISTENING
        HeylanaLog.state("app: voice listening")

        // The debug switch can leave any ear out, so each can be heard on its own.
        val deepgramOn = settings.earAllowed(HeylanaSettings.EAR_DEEPGRAM)
        val assemblyOn = settings.earAllowed(HeylanaSettings.EAR_ASSEMBLYAI)
        val racing = buildSet {
            if (deepgramOn) add(EarsRace.Ear.DEEPGRAM)
            if (assemblyOn) add(EarsRace.Ear.ASSEMBLYAI)
            if (settings.earAllowed(HeylanaSettings.EAR_ANDROID)) add(EarsRace.Ear.ANDROID)
        }
        val running = EarsRace(racing = racing)
        race = running
        val keyterms = Keyterms.forEars(emptyList())
        if (EarsRace.Ear.ANDROID in racing) {
            if (phone.available()) {
                HeylanaLog.state("ears: android listening")
                phone.start(keyterms)
            } else {
                running.failed(EarsRace.Ear.ANDROID, SystemClock.uptimeMillis(), Listener.UNAVAILABLE)
            }
        }
        val proxy = Proxy(settings)
        if ((!deepgramOn && !assemblyOn) || !proxy.isConfigured) {
            if (deepgramOn) deepgramOut("not_set_up")
            if (assemblyOn) assemblyOut("not_set_up")
        } else {
            val deepgram = DeepgramEars(
                proxy = proxy,
                scope = scope,
                heard = callbacksFor(EarsRace.Ear.DEEPGRAM),
                ready = {
                    HeylanaLog.state(
                        "ears: deepgram ready token_ms=${cloud?.tokenMillis ?: -1} socket_ms=${cloud?.socketMillis ?: -1}"
                    )
                },
                unavailable = { reason -> deepgramOut(reason) }
            )
            cloud = deepgram
            // Deepgram switched off: its microphone still runs, for AssemblyAI alone.
            deepgram.micOnly = !deepgramOn
            if (assemblyOn) {
                val third = xyz.heylana.app.voice.AssemblyEars(
                    proxy = proxy,
                    scope = scope,
                    heard = callbacksFor(EarsRace.Ear.ASSEMBLYAI),
                    unavailable = { reason -> assemblyOut(reason) }
                )
                assembly = third
                deepgram.tap = { piece -> third.feed(piece) }
                third.start(keyterms)
            }
            deepgram.start(keyterms)
        }
        main.postDelayed(limit, LISTEN_LIMIT_MS)
        return true
    }

    /** Let go, or tapped again: the ears finish and the race decides. */
    fun finish() {
        val running = race ?: return
        if (phase != Phase.LISTENING) return
        HeylanaLog.state("app: voice released")
        main.removeCallbacks(limit)
        ranMs = SystemClock.uptimeMillis() - startedAt
        phase = Phase.WAITING
        level = 0f
        releasedAt = SystemClock.uptimeMillis()
        judge(running.released(releasedAt))
        phone.release()
        cloud?.release()
        assembly?.release()
        if (race != null) {
            main.postDelayed(raceTick, EarsRace.PREFER_DEEPGRAM_MS)
            main.postDelayed(raceTick, EarsRace.GIVE_UP_MS)
        }
    }

    /** Pause: stop listening and throw away what was said; the mic starts again. */
    fun pause() {
        if (phase != Phase.LISTENING) return
        HeylanaLog.state("app: voice paused")
        ranMs = SystemClock.uptimeMillis() - startedAt
        endRace()
        phase = Phase.PAUSED
    }

    /** Close or back: nothing is sent. */
    fun cancel() {
        if (race != null) HeylanaLog.state("app: voice cancelled")
        endRace()
        heard = ""
        note = null
        phase = Phase.READY
    }

    fun shutdown() {
        cancel()
        phone.shutdown()
    }

    /**
     * The last words each ear showed. In tap-to-talk the phone's recogniser often ends on
     * its own ("no match") after showing the right words as they were said; those words
     * are not thrown away (seen on the Seeker: "Tell me a joke." shown, then nothing heard).
     * Deepgram's are not revived: when it answers the finalize with no speech, its guesses
     * so far were noise (seen on the Seeker: room sound sent as a question).
     */
    private val shown = HashMap<EarsRace.Ear, String>()

    private fun callbacksFor(ear: EarsRace.Ear) = EarCallbacks(
        onPartial = { text ->
            if (race != null) shown[ear] = text
            if (ear == EarsRace.Ear.DEEPGRAM) deepgramSpoke = true
            if (ear == EarsRace.Ear.ASSEMBLYAI) assemblySpoke = true
            val showThis = when (ear) {
                EarsRace.Ear.DEEPGRAM -> true
                EarsRace.Ear.ASSEMBLYAI -> !deepgramSpoke
                EarsRace.Ear.ANDROID -> !deepgramSpoke && !assemblySpoke
            }
            if (showThis) heard = text
        },
        onFinal = { text ->
            HeylanaLog.state("ears: ${ear.name.lowercase()} final")
            val confidence = when (ear) {
                EarsRace.Ear.DEEPGRAM -> cloud?.confidence
                EarsRace.Ear.ASSEMBLYAI -> assembly?.confidence
                EarsRace.Ear.ANDROID -> phone.confidence
            }
            if (ear != EarsRace.Ear.ANDROID) main.postDelayed(raceTick, EarsRace.COMPARE_MS)
            judge(race?.heard(ear, SystemClock.uptimeMillis(), text, confidence))
        },
        onProblem = { message ->
            HeylanaLog.state("ears: ${ear.name.lowercase()} problem")
            judge(race?.failed(ear, SystemClock.uptimeMillis(), message))
        },
        onNothingHeard = {
            val partial = if (ear == EarsRace.Ear.ANDROID) shown[ear].orEmpty().trim() else ""
            if (partial.isNotEmpty()) {
                HeylanaLog.state("ears: ${ear.name.lowercase()} ended with no final, its shown words kept")
                judge(race?.heard(ear, SystemClock.uptimeMillis(), partial))
            } else {
                HeylanaLog.state("ears: ${ear.name.lowercase()} heard nothing")
                judge(race?.nothing(ear, SystemClock.uptimeMillis()))
            }
        },
        onLevel = { l -> if (phase == Phase.LISTENING) level = l }
    )

    private fun assemblyOut(reason: String) {
        HeylanaLog.state("ears: assemblyai out of the race reason=$reason")
        judge(race?.failed(EarsRace.Ear.ASSEMBLYAI, SystemClock.uptimeMillis(), reason))
    }

    private fun deepgramOut(reason: String) {
        HeylanaLog.state("ears: deepgram out of the race reason=$reason")
        judge(race?.failed(EarsRace.Ear.DEEPGRAM, SystemClock.uptimeMillis(), reason))
    }

    private fun judge(verdict: EarsRace.Verdict?) {
        when (verdict) {
            null, EarsRace.Verdict.Wait, EarsRace.Verdict.Settled -> Unit
            is EarsRace.Verdict.Use -> {
                HeylanaLog.state(
                    "ears=${verdict.ear.name.lowercase()} won reason=${verdict.reason} " +
                        "confidence=${verdict.confidence?.let { "%.2f".format(it) } ?: "none"} " +
                        "ms=${SystemClock.uptimeMillis() - releasedAt} all=[${race?.summary()}]"
                )
                endRace()
                phase = Phase.READY
                heard = ""
                chat.send(verdict.text)
            }
            EarsRace.Verdict.NothingHeard -> {
                HeylanaLog.state("ears: neither heard a word")
                endRace()
                phase = Phase.READY
                heard = ""
                note = NOTHING_HEARD
            }
            is EarsRace.Verdict.Problem -> {
                // What the ear said stays in the log; the user reads the plain line.
                HeylanaLog.state("ears: problem reported, shown as plain")
                xyz.heylana.app.ops.CrashReports.problem("ears", "ears", null, null)
                endRace()
                phase = Phase.READY
                heard = ""
                note = xyz.heylana.app.brain.PlainError.forEars(verdict.message)
            }
        }
    }

    private fun endRace() {
        main.removeCallbacks(raceTick)
        main.removeCallbacks(limit)
        race = null
        deepgramSpoke = false
        assemblySpoke = false
        shown.clear()
        level = 0f
        phone.cancel()
        cloud?.cancel()
        cloud = null
        assembly?.cancel()
        assembly = null
    }

    companion object {
        /** Nothing is listened to for longer than this in one go. */
        const val LISTEN_LIMIT_MS = 20_000L

        const val NOTHING_HEARD = "I didn't hear anything. Tap the mic and try again."
        const val TOO_LONG = "I stopped listening after 20 seconds. Tap the mic to talk."
        const val NEEDS_MIC = "Heylana needs the microphone to listen. Allow it, or type instead."
    }
}

/**
 * What a press on the mic means, decided when the finger lifts. A press that started
 * the listening and was held is hold-to-talk: letting go sends. A short press that
 * started it is tap-to-talk: it keeps listening until the next tap. A press while
 * already listening finishes, however long it was.
 */
object MicPress {
    /** Held at least this long, a press is a hold. */
    const val HOLD_MS = 350L

    enum class OnRelease { FINISH, KEEP_LISTENING }

    fun onRelease(heldMs: Long, startedByThisPress: Boolean): OnRelease = when {
        !startedByThisPress -> OnRelease.FINISH
        heldMs >= HOLD_MS -> OnRelease.FINISH
        else -> OnRelease.KEEP_LISTENING
    }

    /** The timer under the wave: m:ss as two-digit minutes and seconds. */
    fun clock(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) / 1000L)
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}
