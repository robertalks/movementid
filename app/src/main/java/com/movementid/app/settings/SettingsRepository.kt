package com.movementid.app.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.movementid.app.network.AiProvider

/**
 * Gemini API key, which models to run, and whether to verify with Google Search — all encrypted
 * and local to the device.
 */
class SettingsRepository(context: Context) {

    private val prefs = runCatching { createPrefs(context) }.getOrElse {
        // The keystore entry can become unusable after a reinstall, an OS restore or a changed
        // debug signing key. An exception here would kill the app on launch before anything
        // renders, so clear the store and start fresh — worst case the key is re-entered.
        runCatching { context.deleteSharedPreferences(PREFS_NAME) }
        createPrefs(context)
    }

    private fun createPrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Which provider new scans use. */
    fun getProvider(): AiProvider {
        val stored = prefs.getString(KEY_PROVIDER, null)
        return AiProvider.entries.firstOrNull { it.name == stored } ?: AiProvider.GEMINI
    }

    fun setProvider(provider: AiProvider) =
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()

    fun getApiKey(provider: AiProvider = getProvider()): String =
        prefs.getString("${KEY_API}_${provider.name}", "") ?: ""

    fun setApiKey(provider: AiProvider, key: String) =
        prefs.edit().putString("${KEY_API}_${provider.name}", key.trim()).apply()

    fun hasApiKey(provider: AiProvider = getProvider()): Boolean = getApiKey(provider).isNotBlank()

    /**
     * Models to run for a scan. More than one means each is asked independently and the answers
     * are shown side by side — where they agree is a better signal than any single model's
     * confidence score.
     */
    fun getSelectedModels(provider: AiProvider = getProvider()): List<String> {
        val defaults = provider.defaultModels
        val stored = prefs.getStringSet("${KEY_MODELS}_${provider.name}", null)?.toList()
        val custom = getCustomModels(provider)
        val all = ((stored ?: listOf(defaults.first())) + custom).distinct()
        // Preserve the registry's newest-first ordering; custom entries go last.
        return defaults.filter { it in all } + all.filterNot { it in defaults }
    }

    fun setSelectedModels(provider: AiProvider, models: List<String>) {
        val cleaned = models.filter { it.isNotBlank() }.distinct()
        prefs.edit().putStringSet(
            "${KEY_MODELS}_${provider.name}",
            cleaned.ifEmpty { listOf(provider.defaultModels.first()) }.toSet()
        ).apply()
    }

    /** Extra model ids typed by the user, comma-separated, so new releases work immediately. */
    fun getCustomModels(provider: AiProvider = getProvider()): List<String> =
        getCustomModelsRaw(provider).split(",").map { it.trim() }.filter { it.isNotBlank() }

    fun getCustomModelsRaw(provider: AiProvider = getProvider()): String =
        prefs.getString("${KEY_CUSTOM_MODELS}_${provider.name}", "") ?: ""

    fun setCustomModels(provider: AiProvider, raw: String) =
        prefs.edit().putString("${KEY_CUSTOM_MODELS}_${provider.name}", raw.trim()).apply()

    /**
     * Second pass where Gemini itself searches and rewrites its own specs. Off by default: it
     * costs an extra Gemini request per scan, and that quota is the scarce one.
     */
    fun getVerifyWithSearch(): Boolean = prefs.getBoolean(KEY_VERIFY, false)

    fun setVerifyWithSearch(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_VERIFY, enabled).apply()

    /**
     * Send every scan at full resolution. Off by default: 2048px reads most engraving at about
     * two-thirds the token cost, and a scan that reads nothing can be retried sharper on demand.
     */
    fun getAlwaysSharp(): Boolean = prefs.getBoolean(KEY_ALWAYS_SHARP, false)

    fun setAlwaysSharp(on: Boolean) = prefs.edit().putBoolean(KEY_ALWAYS_SHARP, on).apply()

    // --- Backup ---

    fun getBackupEnabled(): Boolean = prefs.getBoolean(KEY_BACKUP_ON, false)

    fun setBackupEnabled(on: Boolean) = prefs.edit().putBoolean(KEY_BACKUP_ON, on).apply()

    /** WiFi-only by default: a backup with photos is tens of megabytes. */
    fun getBackupWifiOnly(): Boolean = prefs.getBoolean(KEY_BACKUP_WIFI, true)

    fun setBackupWifiOnly(on: Boolean) = prefs.edit().putBoolean(KEY_BACKUP_WIFI, on).apply()

    fun getBackupUrl(): String = prefs.getString(KEY_BACKUP_URL, "") ?: ""

    fun setBackupUrl(url: String) = prefs.edit().putString(KEY_BACKUP_URL, url.trim()).apply()

    fun getBackupUser(): String = prefs.getString(KEY_BACKUP_USER, "") ?: ""

    fun setBackupUser(user: String) = prefs.edit().putString(KEY_BACKUP_USER, user.trim()).apply()

    fun getBackupPassword(): String = prefs.getString(KEY_BACKUP_PASS, "") ?: ""

    fun setBackupPassword(pw: String) = prefs.edit().putString(KEY_BACKUP_PASS, pw.trim()).apply()

    fun backupConfigured(): Boolean =
        getBackupUrl().isNotBlank() && getBackupUser().isNotBlank() && getBackupPassword().isNotBlank()

    fun getBackupsToKeep(): Int = prefs.getInt(KEY_BACKUP_KEEP, 5)

    fun setBackupsToKeep(n: Int) = prefs.edit().putInt(KEY_BACKUP_KEEP, n.coerceIn(1, 20)).apply()

    fun getLastBackupAt(): Long = prefs.getLong(KEY_BACKUP_AT, 0L)

    fun getLastBackupName(): String = prefs.getString(KEY_BACKUP_NAME, "") ?: ""

    fun setLastBackup(at: Long, name: String) =
        prefs.edit().putLong(KEY_BACKUP_AT, at).putString(KEY_BACKUP_NAME, name).apply()

    companion object {
        private const val KEY_ALWAYS_SHARP = "always_sharp"
        private const val KEY_BACKUP_ON = "backup_enabled"
        private const val KEY_BACKUP_WIFI = "backup_wifi_only"
        private const val KEY_BACKUP_URL = "backup_url"
        private const val KEY_BACKUP_USER = "backup_user"
        private const val KEY_BACKUP_PASS = "backup_pass"
        private const val KEY_BACKUP_KEEP = "backup_keep"
        private const val KEY_BACKUP_AT = "backup_at"
        private const val KEY_BACKUP_NAME = "backup_name"
        private const val PREFS_NAME = "movementid_secure_prefs"
        private const val KEY_PROVIDER = "active_provider"
        private const val KEY_API = "api_key"
        private const val KEY_MODELS = "selected_models"
        private const val KEY_CUSTOM_MODELS = "custom_models"
        private const val KEY_VERIFY = "verify_with_search"
    }
}
