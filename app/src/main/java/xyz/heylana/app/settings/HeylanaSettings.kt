@file:Suppress("DEPRECATION") // EncryptedSharedPreferences is still the supported
// on-device secret store for this minSdk; revisit when a replacement ships.

package xyz.heylana.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted on-device storage for the API key and model name.
 *
 * The key lives here and nowhere else: not in source, not in logs, not in git.
 */
class HeylanaSettings private constructor(private val prefs: SharedPreferences) {

    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_API_KEY) else putString(KEY_API_KEY, value.trim())
            }.apply()
        }

    /**
     * The id this install is known by at the proxy. Made once, kept until the
     * app is uninstalled, and sent with every request so a single phone cannot
     * spend the whole day's budget.
     */
    val deviceId: String
        get() = DeviceId.readOrCreate(
            read = { prefs.getString(KEY_DEVICE_ID, null) },
            write = { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
        )

    /**
     * An address for the proxy that overrides the one built into the app. For
     * pointing a test build at a stub, and empty the rest of the time.
     */
    var proxyUrlOverride: String
        get() = prefs.getString(KEY_PROXY_URL, null).orEmpty()
        set(value) {
            val cleaned = value.trim().trimEnd('/')
            prefs.edit().apply {
                if (cleaned.isEmpty()) remove(KEY_PROXY_URL) else putString(KEY_PROXY_URL, cleaned)
            }.apply()
        }

    /**
     * Off by default and hidden away: talk to Anthropic directly with a key of
     * your own instead of going through Heylana's proxy. Nothing else — the
     * voice and the ears still need the proxy, because their keys are not the
     * user's to hold.
     */
    var useOwnKey: Boolean
        get() = prefs.getBoolean(KEY_USE_OWN_KEY, false) && apiKey != null
        set(value) {
            prefs.edit().putBoolean(KEY_USE_OWN_KEY, value).apply()
        }

    /** Which voice reads the answers out. */
    var voice: String
        get() = prefs.getString(KEY_VOICE, null)?.takeIf { it in VOICES } ?: VOICE_SKYLAR
        set(value) {
            prefs.edit().putString(KEY_VOICE, if (value in VOICES) value else VOICE_SKYLAR).apply()
        }

    /**
     * Debug switch: which ears listen. [EAR_AUTO] races all three; any other one is the only
     * ear that listens, so each provider can be heard on its own.
     */
    var forceEar: String
        get() = prefs.getString(KEY_FORCE_EAR, null)?.takeIf { it in FORCE_EARS }
            ?: if (prefs.getBoolean(KEY_FORCE_PHONE_EARS, false)) EAR_ANDROID else EAR_AUTO
        set(value) {
            val ear = value.takeIf { it in FORCE_EARS } ?: EAR_AUTO
            prefs.edit().putString(KEY_FORCE_EAR, ear).putBoolean(KEY_FORCE_PHONE_EARS, ear == EAR_ANDROID).apply()
        }

    /** The old switch: only the phone's own ears. */
    var forcePhoneEars: Boolean
        get() = forceEar == EAR_ANDROID
        set(value) {
            forceEar = if (value) EAR_ANDROID else EAR_AUTO
        }

    /**
     * Whether AssemblyAI listens: the worker lends it (as /me last said; until heard, it is
     * assumed, as the worker is set) and the debug switch has not left it out. The privacy
     * line names it exactly then.
     */
    val assemblyListening: Boolean
        get() = EAR_ASSEMBLYAI in (prefs.getString(KEY_EARS_OFFERED, null)?.split(",") ?: listOf(EAR_ASSEMBLYAI)) &&
            earAllowed(EAR_ASSEMBLYAI) && forceEar != EAR_ANDROID

    /** The week whose "what I caught" card has been seen, so it shows once a week. */
    var weekCardSeen: String
        get() = prefs.getString(KEY_WEEK_SEEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_WEEK_SEEN, value).apply()
        }

    /** Whether [ear] ("deepgram", "assemblyai", "android") may listen under the debug switch. */
    fun earAllowed(ear: String): Boolean = forceEar.let { it == EAR_AUTO || it == ear }

    /**
     * Debug switch: keeps the raw audio of the last spoken answer in a file, so
     * a stream that sounds wrong on the phone can be listened to somewhere else.
     */
    /**
     * Debug builds only: each ear's words from the last hold, kept in the app's private
     * storage as ears_capture.txt (overwritten every hold), so a test can count word errors.
     * Off unless switched on; never in a log.
     */
    var saveEarsCapture: Boolean
        get() = prefs.getBoolean(KEY_SAVE_EARS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SAVE_EARS, value).apply()
        }

    var saveTtsStream: Boolean
        get() = prefs.getBoolean(KEY_SAVE_TTS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SAVE_TTS, value).apply()
        }

    /** Whether Heylana's spoken answers are silenced. Default: it speaks. */
    var voiceMuted: Boolean
        get() = prefs.getBoolean(KEY_VOICE_MUTED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_VOICE_MUTED, value).apply()
        }

    /**
     * Whether a spoken answer also shows its text. Off by default: asking by
     * voice is asking to be answered by voice.
     */
    var showTextForVoice: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TEXT_VOICE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_TEXT_VOICE, value).apply()
        }

    /**
     * Whether the connection to the API is opened as soon as the buddy is
     * touched. On by default; the switch exists so the gain can be measured with
     * it off, and is only shown in debug builds.
     */
    var warmUpConnection: Boolean
        get() = prefs.getBoolean(KEY_WARM_UP, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WARM_UP, value).apply()
        }

    private val walletStore = xyz.heylana.app.wallet.WalletSessionStore(
        read = { prefs.getString(it, null) },
        write = { key, value ->
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    )

    /**
     * The connected wallet: its address and the session the worker sealed for
     * it. Encrypted with everything else here; null when no wallet is connected.
     */
    var walletSession: xyz.heylana.app.wallet.WalletSession?
        get() = walletStore.load()
        set(value) {
            if (value == null) walletStore.clear() else walletStore.save(value)
        }

    /**
     * A payment the wallet sent that the worker had not yet seen confirmed:
     * (reference, signature). Settings asks about it again when it next opens,
     * so a slow network cannot cost the user what they paid for.
     */
    var pendingPayment: Pair<String, String>?
        get() = prefs.getString(KEY_PENDING_PAYMENT, null)
            ?.split(' ')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_PENDING_PAYMENT)
                else putString(KEY_PENDING_PAYMENT, "${value.first} ${value.second}")
            }.apply()
        }

    /**
     * What Heylana calls the user, copied here from their wallet's profile so the
     * buddy can greet them without asking the worker first. Empty when unset or
     * when no wallet is connected.
     */
    var callMe: String
        get() = prefs.getString(KEY_CALL_ME, null).orEmpty()
        set(value) {
            val cleaned = xyz.heylana.app.wallet.cleanName(value)
            prefs.edit().apply {
                if (cleaned.isEmpty()) remove(KEY_CALL_ME) else putString(KEY_CALL_ME, cleaned)
            }.apply()
        }

    /** The skills the user has switched off. Everything else is on, including a new install. */
    var skillsOff: Set<String>
        get() = prefs.getStringSet(KEY_SKILLS_OFF, null)?.toSet().orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_SKILLS_OFF, value.toSet()).apply()
        }

    /**
     * How many skills the plan allows, as the worker's /me last said it, so the buddy
     * can pick a skill without asking. Null until Settings has heard it once.
     */
    /** Which provider speaks, as /me last said: the privacy line names it. */
    val voiceProvider: String
        get() = prefs.getString(KEY_VOICE_PROVIDER, null) ?: VoiceCopy.DEFAULT_PROVIDER

    /** What the picker calls [slot]: the name /me gave it, else the provider's own. */
    fun voiceName(slot: String): String =
        prefs.getString(KEY_VOICE_NAME_PREFIX + slot, null)?.takeIf { it.isNotBlank() }
            ?: VoiceCopy.defaultName(voiceProvider, slot)

    /** Keeps what /me said about the voice: the provider and the two slots' names. */
    fun rememberVoice(provider: String, skylar: String?, archie: String?, ears: List<String>? = null) {
        prefs.edit().apply {
            putString(KEY_VOICE_PROVIDER, provider)
            if (ears != null) putString(KEY_EARS_OFFERED, ears.joinToString(","))
            if (skylar.isNullOrBlank()) remove(KEY_VOICE_NAME_PREFIX + VOICE_SKYLAR) else putString(KEY_VOICE_NAME_PREFIX + VOICE_SKYLAR, skylar)
            if (archie.isNullOrBlank()) remove(KEY_VOICE_NAME_PREFIX + VOICE_ARCHIE) else putString(KEY_VOICE_NAME_PREFIX + VOICE_ARCHIE, archie)
        }.apply()
    }

    var skillsCap: Int?
        get() = prefs.getInt(KEY_SKILLS_CAP, 0).takeIf { it > 0 }
        set(value) {
            prefs.edit().apply {
                if (value == null || value <= 0) remove(KEY_SKILLS_CAP) else putInt(KEY_SKILLS_CAP, value)
            }.apply()
        }

    /**
     * "Darker glass": a black 25% base under the clear glass, for people who mostly use
     * light apps, where clear glass and white text wash out. Off by default.
     */
    /** The app's glass: "dark" (the design, on black) or "light". Settings → Glass mode. */
    var glassMode: String
        get() = prefs.getString(KEY_GLASS_MODE, null)?.takeIf { it == GLASS_LIGHT || it == GLASS_DARK } ?: GLASS_DARK
        set(value) {
            prefs.edit().putString(KEY_GLASS_MODE, if (value == GLASS_LIGHT) GLASS_LIGHT else GLASS_DARK).apply()
        }

    /** True once the first run (sign in, name, permissions) has been through to Home once. */
    var firstRunDone: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FIRST_RUN_DONE, value).apply()
        }

    /** A copy of whether memory is on for this wallet (the worker holds the truth), so the buddy need not ask. */
    var memoryOn: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_ON, false)
        set(value) {
            prefs.edit().putBoolean(KEY_MEMORY_ON, value).apply()
        }

    /** The preferences already offered to be remembered on this phone: each is offered once. */
    var memoryOffered: Set<String>
        get() = prefs.getStringSet(KEY_MEMORY_OFFERED, null)?.toSet().orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_MEMORY_OFFERED, value.toSet()).apply()
        }

    /** Set once the user has confirmed a send on this phone: the Seed Vault trust hint is shown until then. */
    var sendConfirmedOnce: Boolean
        get() = prefs.getBoolean(KEY_SEND_CONFIRMED_ONCE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SEND_CONFIRMED_ONCE, value).apply()
        }

    /**
     * Watch signing screens: the buddy wakes by itself on a wallet's confirm sheet, and on
     * a page asking for a recovery phrase. On unless the user turns it off.
     */
    var watchSigning: Boolean
        get() = prefs.getBoolean(KEY_WATCH_SIGNING, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WATCH_SIGNING, value).apply()
        }

    /**
     * Debug builds only: treat any screen carrying a confirm sheet's words as a signing
     * screen, so the glance can be timed without a wallet's own sheet in front.
     */
    var glanceAnyScreen: Boolean
        get() = prefs.getBoolean(KEY_GLANCE_ANY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GLANCE_ANY, value).apply()
        }

    /** The day of the phishing list the phone holds, so it only downloads a newer one. */
    var blocklistVersion: String
        get() = prefs.getString(KEY_BLOCKLIST_VERSION, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_BLOCKLIST_VERSION, value).apply()
        }

    /** When it last asked for one, so it asks at most once a day. */
    var blocklistCheckedAt: Long
        get() = prefs.getLong(KEY_BLOCKLIST_CHECKED, 0L)
        set(value) {
            prefs.edit().putLong(KEY_BLOCKLIST_CHECKED, value).apply()
        }

    var darkerGlass: Boolean
        get() = prefs.getBoolean(KEY_DARKER_GLASS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DARKER_GLASS, value).apply()
        }

    /** Debug switch: count skills against the Free plan's cap whatever the plan is. */
    var simulateFreePlan: Boolean
        get() = prefs.getBoolean(KEY_SIMULATE_FREE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SIMULATE_FREE, value).apply()
        }

    val hasApiKey: Boolean get() = apiKey != null

    /** "sk-ant-…4f2a" — enough to recognise the key, never enough to use it. */
    fun maskedApiKey(): String? {
        val key = apiKey ?: return null
        if (key.length <= 12) return "•".repeat(key.length)
        return key.take(7) + "…" + key.takeLast(4)
    }

    companion object {

        /**
         * The two voice slots, as the worker knows them. There is no phone voice: a
         * stored "phone" from an older build reads as the first slot.
         */
        const val VOICE_SKYLAR = "skylar"
        const val VOICE_ARCHIE = "archie"
        val VOICES = listOf(VOICE_SKYLAR, VOICE_ARCHIE)



        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PENDING_PAYMENT = "pending_payment"
        private const val KEY_CALL_ME = "call_me"
        private const val KEY_PROXY_URL = "proxy_url"
        private const val KEY_USE_OWN_KEY = "use_own_key"
        private const val KEY_VOICE = "voice"
        private const val KEY_FORCE_PHONE_EARS = "force_phone_ears"
        private const val KEY_FORCE_EAR = "force_ear"
        private const val KEY_EARS_OFFERED = "ears_offered"
        private const val KEY_WEEK_SEEN = "week_card_seen"
        const val EAR_AUTO = "auto"
        const val EAR_DEEPGRAM = "deepgram"
        const val EAR_ASSEMBLYAI = "assemblyai"
        const val EAR_ANDROID = "android"
        val FORCE_EARS = listOf(EAR_AUTO, EAR_DEEPGRAM, EAR_ASSEMBLYAI, EAR_ANDROID)
        private const val KEY_SAVE_TTS = "save_tts_stream"
        private const val KEY_SAVE_EARS = "save_ears_capture"

        private const val KEY_WARM_UP = "warm_up_connection"
        private const val KEY_SKILLS_OFF = "skills_off"
        private const val KEY_SKILLS_CAP = "skills_cap"
        private const val KEY_VOICE_PROVIDER = "voice_provider"
        private const val KEY_VOICE_NAME_PREFIX = "voice_name_"
        private const val KEY_SIMULATE_FREE = "simulate_free_plan"
        private const val KEY_DARKER_GLASS = "darker_glass"
        private const val KEY_WATCH_SIGNING = "watch_signing"
        private const val KEY_GLANCE_ANY = "glance_any_screen"
        private const val KEY_BLOCKLIST_VERSION = "blocklist_version"
        private const val KEY_BLOCKLIST_CHECKED = "blocklist_checked_at"
        private const val KEY_GLASS_MODE = "glass_mode"
        private const val KEY_FIRST_RUN_DONE = "first_run_done"
        private const val KEY_SEND_CONFIRMED_ONCE = "send_confirmed_once"
        private const val KEY_MEMORY_ON = "memory_on"
        private const val KEY_MEMORY_OFFERED = "memory_offered"
        const val GLASS_DARK = "dark"
        const val GLASS_LIGHT = "light"

        private const val FILE_NAME = "heylana_secure_settings"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_VOICE_MUTED = "voice_muted"
        private const val KEY_SHOW_TEXT_VOICE = "show_text_voice"

        @Volatile
        private var instance: HeylanaSettings? = null

        fun get(context: Context): HeylanaSettings =
            instance ?: synchronized(this) {
                instance ?: HeylanaSettings(open(context.applicationContext)).also { instance = it }
            }

        private fun open(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
