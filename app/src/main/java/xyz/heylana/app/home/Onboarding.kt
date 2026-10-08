package xyz.heylana.app.home

/**
 * The first run, said out loud.
 *
 * A judge installs the APK cold and used to meet silent permission screens; her voice is
 * the product, so she now speaks each step as it happens, with the same words on screen.
 * Everything here is plain Kotlin — the order, the lines and when each is said — so the
 * flow is tested on the JVM and the Compose screen only draws it.
 *
 * Nothing waits on audio. If the voice cannot speak (no network, over its limit) the same
 * text is on screen and the flow runs exactly as it would have; see [OnboardingFlow.next].
 */
enum class OnboardingStep {
    /** Who she is, before anything is asked for. */
    WELCOME,

    /** The permission rows. Two of her lines are said here, as each is granted. */
    PERMISSIONS,

    /** Sign in with the wallet, or go on without one. */
    WALLET,

    /** What to call them, and how to say it. */
    NAME,

    /** The promise the product is really about: she speaks up before a signature. */
    SIGNING,

    /** Handing over. */
    HANDOVER,

    /** Out of the flow and into Home. */
    DONE
}

/**
 * A line she says. The step a line belongs to is not always the step the user is on:
 * [SCREEN_READING] and [ORB] are said the moment each permission is granted, while the
 * permissions screen is still up.
 */
enum class Moment { WELCOME, SCREEN_READING, ORB, WALLET, NAME_CHECK, SIGNING, HANDOVER }

/** Every word she says in the first run, in one place. */
object OnboardingText {

    const val WELCOME =
        "Hey. I'm Heylana. I'm your Seeker buddy and I live on your screen, so you can ask me " +
            "about anything on it. I'll need two things from you first."

    const val SCREEN_READING = "Got it. I can see your screen now, but only when you hold me."

    const val ORB = "That's me. Hold me any time and ask."

    const val WALLET =
        "Connected. I can read your wallet, but I can never move anything. Every signature is " +
            "your fingerprint, not mine."

    const val SIGNING =
        "Last thing, and it's the important one. When your wallet asks you to approve something, " +
            "I'll speak up first and tell you what it actually does. You never have to guess again."

    const val HANDOVER = "That's it. Open any app, hold me, and ask what you're looking at."

    /** The pronunciation check, on whatever name we have: their .skr name, or what they typed. */
    fun nameCheck(name: String): String = "Your Seeker says you're $name. Did I say that right?"

    /** Said after a new spelling is given, so they hear it before keeping it. */
    fun readBack(name: String): String = "$name. Better?"

    /** On screen under the respelling field: how the hint is read. */
    const val RESPELL_HINT = "Og-heh-neh-roo-KEV-weh"
    const val RESPELL_HOW =
        "Hyphens separate syllables, CAPITALS mark the stressed one. Only I use this; your name " +
            "stays spelled the way you wrote it."

    /** Shown and said once the tries are used up, so nobody is stuck on it. */
    const val GOOD_ENOUGH = "We can leave it there and fix it later in Settings."
}

/** How many times she will try a new pronunciation before offering to move on. */
const val PRONUNCIATION_TRIES = 3

object OnboardingFlow {

    /** The words for a moment; [NAME_CHECK] has no fixed line, since it carries the name. */
    fun line(moment: Moment): String? = when (moment) {
        Moment.WELCOME -> OnboardingText.WELCOME
        Moment.SCREEN_READING -> OnboardingText.SCREEN_READING
        Moment.ORB -> OnboardingText.ORB
        Moment.WALLET -> OnboardingText.WALLET
        Moment.SIGNING -> OnboardingText.SIGNING
        Moment.HANDOVER -> OnboardingText.HANDOVER
        Moment.NAME_CHECK -> null
    }

    /** The line said on arriving at a step, if any. */
    fun arrivingAt(step: OnboardingStep): Moment? = when (step) {
        OnboardingStep.WELCOME -> Moment.WELCOME
        OnboardingStep.SIGNING -> Moment.SIGNING
        OnboardingStep.HANDOVER -> Moment.HANDOVER
        OnboardingStep.NAME -> Moment.NAME_CHECK
        OnboardingStep.PERMISSIONS, OnboardingStep.WALLET, OnboardingStep.DONE -> null
    }

    /**
     * What she says when the permissions change under her: screen reading first, then the
     * overlay, which is the moment the orb can finally appear. Only ever on the way on —
     * a permission turned off again says nothing.
     */
    fun granted(before: PermissionState, after: PermissionState): List<Moment> = buildList {
        if (!before.screenReading && after.screenReading) add(Moment.SCREEN_READING)
        if (!before.overlay && after.overlay) add(Moment.ORB)
    }

    /** The step after this one. The flow never goes back, and never waits on her voice. */
    fun next(step: OnboardingStep): OnboardingStep = when (step) {
        OnboardingStep.WELCOME -> OnboardingStep.PERMISSIONS
        OnboardingStep.PERMISSIONS -> OnboardingStep.WALLET
        OnboardingStep.WALLET -> OnboardingStep.NAME
        OnboardingStep.NAME -> OnboardingStep.SIGNING
        OnboardingStep.SIGNING -> OnboardingStep.HANDOVER
        OnboardingStep.HANDOVER, OnboardingStep.DONE -> OnboardingStep.DONE
    }

    /** The order, for the test and for anyone reading it. */
    val order: List<OnboardingStep> = listOf(
        OnboardingStep.WELCOME,
        OnboardingStep.PERMISSIONS,
        OnboardingStep.WALLET,
        OnboardingStep.NAME,
        OnboardingStep.SIGNING,
        OnboardingStep.HANDOVER
    )

    /**
     * Skip, from any step. It leaves a working app: the first run is over, and whatever was
     * granted stays granted. Nothing here is required to reach Home.
     */
    fun skip(): OnboardingStep = OnboardingStep.DONE

    /** Whether the flow is still running: Home takes over at [OnboardingStep.DONE]. */
    fun running(step: OnboardingStep): Boolean = step != OnboardingStep.DONE
}
