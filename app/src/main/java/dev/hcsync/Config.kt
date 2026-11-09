package dev.hcsync

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Endpoint settings, kept in app-private storage (backups are disabled in the manifest).
 * The token is never written to logs or to the screen in full.
 */
class Config(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    var url: String
        get() = p.getString("url", "") ?: ""
        set(v) = p.edit().putString("url", v.trim()).apply()

    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v.trim()).apply()

    var daysBack: Int
        get() = p.getInt("days_back", 30)
        set(v) = p.edit().putInt("days_back", v.coerceIn(1, 3650)).apply()

    /** Epoch millis of the last fully successful sync; 0 if none. */
    var lastSync: Long
        get() = p.getLong("last_sync", 0L)
        set(v) = p.edit().putLong("last_sync", v).apply()

    val isConfigured: Boolean get() = url.startsWith("https://") && token.isNotBlank()

    val tokenHint: String
        get() = if (token.length < 8) (if (token.isEmpty()) "(not set)" else "(set)") else "…${token.takeLast(4)}"

    /**
     * One-time provisioning: if `files/config.json` exists (pushed with `adb run-as`), import it and delete it,
     * so the token never has to be typed on the phone.
     */
    fun importProvisioningFile(): Boolean {
        val f = File(ctx.filesDir, "config.json")
        if (!f.exists()) return false
        return try {
            val j = JSONObject(f.readText())
            j.optString("url").takeIf { it.isNotBlank() }?.let { url = it }
            j.optString("token").takeIf { it.isNotBlank() }?.let { token = it }
            if (j.has("days_back")) daysBack = j.getInt("days_back")
            true
        } catch (_: Exception) {
            false
        } finally {
            f.delete()
        }
    }
}
