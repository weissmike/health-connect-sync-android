package dev.hcsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Reads the granted Health Connect record types and POSTs them to the configured `/ingest` endpoint.
 *
 * Strategy: the first sync reads `daysBack` days; later syncs re-read from 48 hours before the last
 * successful sync. Overlap is safe because the server upserts by Health Connect's record id, and it also
 * picks up edits made to recent records. Deletions are not propagated.
 */
class Syncer(
    private val ctx: Context,
    private val cfg: Config,
    private val log: (String) -> Unit,
) {
    data class Outcome(val ok: Boolean, val message: String)

    suspend fun run(): Outcome = withContext(Dispatchers.IO) {
        if (!cfg.isConfigured) return@withContext Outcome(false, "Set an https:// URL and the token first.")
        val status = HealthConnectClient.getSdkStatus(ctx)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            return@withContext Outcome(false, "Health Connect is not available on this phone (status $status).")
        }
        val client = HealthConnectClient.getOrCreate(ctx)
        val granted = client.permissionController.getGrantedPermissions()
        val specs = SPECS.filter { HealthPermission.getReadPermission(it.cls) in granted }
        if (specs.isEmpty()) return@withContext Outcome(false, "No Health Connect read permissions granted yet.")

        val end = Instant.now()
        val start = if (cfg.lastSync == 0L) {
            end.minus(cfg.daysBack.toLong(), ChronoUnit.DAYS)
        } else {
            Instant.ofEpochMilli(cfg.lastSync).minus(48, ChronoUnit.HOURS)
        }
        log("Syncing ${specs.size} types since $start")

        var failed = 0
        var total = 0
        for (spec in specs) {
            try {
                val n = syncType(client, spec, start, end)
                total += n
                if (n > 0) log("${spec.name}: sent $n")
            } catch (e: Exception) {
                failed++
                log("${spec.name}: FAILED ${e.javaClass.simpleName}: ${e.message?.take(140)}")
            }
        }
        if (failed == 0) cfg.lastSync = end.toEpochMilli()
        Outcome(failed == 0, if (failed == 0) "Done: $total records sent." else "Finished with $failed failed type(s); $total records sent. Will retry the full window next time.")
    }

    private suspend fun <T : Record> syncType(client: HealthConnectClient, spec: Spec<T>, start: Instant, end: Instant): Int {
        var pageToken: String? = null
        var sent = 0
        var batch = JSONArray()
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = spec.cls,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = 1000,
                    pageToken = pageToken,
                ),
            )
            for (r in resp.records) {
                val j = spec.convert(r) ?: continue
                batch.put(j)
                if (batch.length() >= BATCH_SIZE) {
                    post(spec.name, batch)
                    sent += batch.length()
                    batch = JSONArray()
                }
            }
            pageToken = resp.pageToken?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        if (batch.length() > 0) {
            post(spec.name, batch)
            sent += batch.length()
        }
        return sent
    }

    private fun endpoint(): URL {
        val u = cfg.url.trim()
        val withPath = if (u.substringAfter("://").contains('/') && !u.endsWith("/")) u else u.trimEnd('/') + "/ingest"
        return URL(withPath)
    }

    private fun post(type: String, items: JSONArray) {
        val body = JSONObject()
            .put("timestamp", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("app_version", "hcsync/${APP_VERSION}")
            .put("timezone", ZoneId.systemDefault().id)
            .put(type, items)
            .toString()
            .toByteArray(Charsets.UTF_8)
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val c = endpoint().openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 20_000
                    c.readTimeout = 60_000
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setRequestProperty("Authorization", "Bearer ${cfg.token}")
                    c.outputStream.use { it.write(body) }
                    val code = c.responseCode
                    if (code in 200..299) return
                    val detail = c.errorStream?.bufferedReader()?.readText()?.take(120) ?: ""
                    // 4xx will not fix itself on retry (bad token, payload too large): fail fast.
                    if (code in 400..499) throw IOException("HTTP $code $detail")
                    lastError = IOException("HTTP $code $detail")
                } finally {
                    c.disconnect()
                }
            } catch (e: IOException) {
                lastError = e
                if (e.message?.startsWith("HTTP 4") == true) throw e
            }
            if (attempt == 0) Thread.sleep(2_000)
        }
        throw lastError ?: IOException("upload failed")
    }

    companion object {
        const val BATCH_SIZE = 300
        const val APP_VERSION = "0.1.0"
    }
}
