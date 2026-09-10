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

    /** The cheap model, used for one-shot questions. */
    var quickModel: String
        get() = prefs.getString(KEY_QUICK_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_QUICK_MODEL
        set(value) {
            val cleaned = value.trim().ifBlank { DEFAULT_QUICK_MODEL }
            prefs.edit().putString(KEY_QUICK_MODEL, cleaned).apply()
        }

    /**
     * The stronger model, used for the steps of a guidance task.
     *
     * Falls back to the single "model" setting older builds wrote, so anyone
     * upgrading keeps the model they had chosen for the harder work.
     */
    var taskModel: String
        get() = prefs.getString(KEY_TASK_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_LEGACY_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TASK_MODEL
        set(value) {
            val cleaned = value.trim().ifBlank { DEFAULT_TASK_MODEL }
            prefs.edit().putString(KEY_TASK_MODEL, cleaned).apply()
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

    val hasApiKey: Boolean get() = apiKey != null

    /** "sk-ant-…4f2a" — enough to recognise the key, never enough to use it. */
    fun maskedApiKey(): String? {
        val key = apiKey ?: return null
        if (key.length <= 12) return "•".repeat(key.length)
        return key.take(7) + "…" + key.takeLast(4)
    }

    companion object {
        const val DEFAULT_QUICK_MODEL = "claude-haiku-4-5-20251001"
        const val DEFAULT_TASK_MODEL = "claude-sonnet-5"

        private const val FILE_NAME = "heylana_secure_settings"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_LEGACY_MODEL = "model"
        private const val KEY_QUICK_MODEL = "quick_model"
        private const val KEY_TASK_MODEL = "task_model"
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
