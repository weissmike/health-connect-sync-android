package dev.hcsync

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var cfg: Config
    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var daysField: EditText
    private lateinit var statusView: TextView
    private lateinit var syncButton: Button

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        val types = SPECS.count { HealthPermission.getReadPermission(it.cls) in granted }
        append("Permissions: $types of ${SPECS.size} data types granted.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        val imported = cfg.importProvisioningFile()
        setContentView(buildUi())
        if (imported) append("Imported settings from provisioning file (file deleted).")
        append(summary())
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            append("Health Connect is not available: install or update it from Google Play.")
        }
    }

    private fun summary(): String {
        val last = if (cfg.lastSync == 0L) "never" else DateFormat.getDateTimeInstance().format(Date(cfg.lastSync))
        return "URL: ${cfg.url.ifBlank { "(not set)" }}\nToken: ${cfg.tokenHint}\nLast full sync: $last"
    }

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }

        col.addView(TextView(this).apply { text = "HC Sync"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
        col.addView(TextView(this).apply {
            text = "Sends your Health Connect data to your own server. Read-only access; nothing leaves the phone except to the URL below."
            setPadding(0, pad / 2, 0, pad)
        })

        urlField = EditText(this).apply {
            hint = "https://your-host/ingest"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(cfg.url)
        }
        tokenField = EditText(this).apply {
            hint = "Ingest token (leave blank to keep ${cfg.tokenHint})"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        daysField = EditText(this).apply {
            hint = "Days to read on the first sync"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cfg.daysBack.toString())
        }
        col.addView(urlField)
        col.addView(tokenField)
        col.addView(daysField)

        col.addView(button("1. Grant Health Connect permissions") {
            saveFields()
            val all = SPECS.map { HealthPermission.getReadPermission(it.cls) }.toMutableSet()
            all += HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
            all += HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
            permissionLauncher.launch(all)
        })
        syncButton = button("2. Sync now") { syncNow() }
        col.addView(syncButton)
        col.addView(button("3. Enable background sync (every 6 hours)") {
            saveFields()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(SyncWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
            append("Background sync scheduled. It needs the 'background' permission granted in step 1.")
        })
        col.addView(button("Stop background sync") {
            WorkManager.getInstance(this).cancelUniqueWork(SyncWorker.UNIQUE_NAME)
            append("Background sync stopped.")
        })

        statusView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(0, pad, 0, 0)
            setTextIsSelectable(true)
        }
        col.addView(statusView)
        return ScrollView(this).apply { addView(col) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun saveFields() {
        cfg.url = urlField.text.toString()
        tokenField.text.toString().takeIf { it.isNotBlank() }?.let { cfg.token = it; tokenField.setText("") }
        daysField.text.toString().toIntOrNull()?.let { cfg.daysBack = it }
    }

    private fun syncNow() {
        saveFields()
        syncButton.isEnabled = false
        append("Starting sync...")
        lifecycleScope.launch {
            val outcome = try {
                Syncer(this@MainActivity, cfg) { append(it) }.run()
            } catch (e: Exception) {
                Syncer.Outcome(false, "Error: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
            }
            append(outcome.message)
            append(summary())
            syncButton.isEnabled = true
        }
    }

    private fun append(line: String) {
        runOnUiThread {
            statusView.text = "${statusView.text}${if (statusView.text.isEmpty()) "" else "\n"}$line"
        }
    }
}
