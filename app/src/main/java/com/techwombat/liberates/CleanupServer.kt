package com.techwombat.liberates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Address and access key for the Wombat Cleanup server (the key is stored encrypted). */
object CleanupServerSettings {
    const val DEFAULT_URL = "https://cleanup.wombatex.com"
    private const val PREFERENCES_NAME = "cleanup_server"
    private const val URL_KEY = "url"
    private const val ENCRYPTED_KEY = "encrypted_key"

    data class Settings(val url: String, val key: String)

    fun url(context: Context): String =
        preferences(context).getString(URL_KEY, null)?.ifBlank { null } ?: DEFAULT_URL

    /** Null until an access key has been saved. */
    fun load(context: Context): Settings? {
        val key = KeystoreText.decrypt(preferences(context).getString(ENCRYPTED_KEY, null)) ?: return null
        return Settings(url(context), key)
    }

    fun save(context: Context, url: String, key: String) {
        preferences(context).edit()
            .putString(URL_KEY, url.trim().trimEnd('/'))
            .putString(ENCRYPTED_KEY, KeystoreText.encrypt(key.trim()))
            .apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}

/** A problem talking to the cleanup server, with a message meant for people. */
class CleanupException(message: String, val jobGone: Boolean = false) : IOException(message)

/** Blocking client for the Wombat Cleanup API; call from a background dispatcher. */
object CleanupClient {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 120_000

    data class JobStatus(
        val status: String,
        val done: Int,
        val total: Int,
        val queuePosition: Int?,
        val etaSeconds: Long?,
        val model: String,
        val error: String?,
    ) {
        val finished: Boolean get() = status == "done"
        val failed: Boolean get() = status == "error"
    }

    data class JobResult(val chunks: List<String>, val model: String, val seconds: Long)

    /** Checks the address and key; returns the server's default model. */
    fun test(settings: CleanupServerSettings.Settings): String = parseModels(request(settings, "GET", "/models"))

    /** Uploads a book's chunks; returns the new job's id and model. */
    fun submit(settings: CleanupServerSettings.Settings, title: String, chunks: List<String>): Pair<String, String> {
        val body = JSONObject().put("title", title).put("chunks", JSONArray(chunks)).toString()
        val response = JSONObject(request(settings, "POST", "/jobs", body))
        return response.getString("job_id") to response.optString("model")
    }

    fun status(settings: CleanupServerSettings.Settings, jobId: String): JobStatus =
        parseStatus(request(settings, "GET", "/jobs/$jobId"))

    fun result(settings: CleanupServerSettings.Settings, jobId: String): JobResult =
        parseResult(request(settings, "GET", "/jobs/$jobId/result"))

    fun delete(settings: CleanupServerSettings.Settings, jobId: String) {
        runCatching { request(settings, "DELETE", "/jobs/$jobId") }
    }

    fun parseModels(json: String): String = JSONObject(json).optString("default")

    fun parseStatus(json: String): JobStatus {
        val o = JSONObject(json)
        return JobStatus(
            status = o.getString("status"),
            done = o.optInt("done"),
            total = o.optInt("total"),
            queuePosition = if (o.isNull("queue_position")) null else o.optInt("queue_position"),
            etaSeconds = if (o.isNull("eta_seconds")) null else o.optLong("eta_seconds"),
            model = o.optString("model"),
            error = if (o.isNull("error")) null else o.optString("error"),
        )
    }

    fun parseResult(json: String): JobResult {
        val o = JSONObject(json)
        val array = o.getJSONArray("chunks")
        return JobResult(List(array.length()) { array.getString(it) }, o.optString("model"), o.optLong("seconds"))
    }

    private fun request(settings: CleanupServerSettings.Settings, method: String, path: String, body: String? = null): String {
        if (!settings.url.startsWith("https://")) throw CleanupException("The cleanup server address must start with https://")
        val connection = try {
            (URL(settings.url + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer ${settings.key}")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
        } catch (error: Exception) {
            throw CleanupException("The cleanup server address is not valid.")
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) return text
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null }
                ?: "The cleanup server answered with error $code."
            throw CleanupException(message, jobGone = code == 404)
        } catch (error: CleanupException) {
            throw error
        } catch (error: IOException) {
            throw CleanupException("Could not reach the cleanup server (${error.message ?: error.javaClass.simpleName}). Check your internet connection.")
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Keeps books' server cleanup jobs moving: checks progress, downloads finished results as a new
 * version, removes them from the server, and remembers failures for the Review screen.
 */
object CleanupSync {
    private const val WORK_NAME = "cleanup-server-check"
    private const val CHANNEL_ID = "cleanup"

    sealed class Outcome(val workspace: ScanWorkspace) {
        class Finished(workspace: ScanWorkspace, val version: TextVersion) : Outcome(workspace)
        class Failed(workspace: ScanWorkspace, val message: String) : Outcome(workspace)
        class Running(workspace: ScanWorkspace, val status: CleanupClient.JobStatus) : Outcome(workspace)
    }

    fun pendingWorkspaces(context: Context): List<ScanWorkspace> =
        (Workspaces.library(context).books() + Workspaces.test(context)).filter { it.serverJob != null }

    /** Checks one workspace's job. Blocking. Returns null if it has no job. */
    fun check(context: Context, workspace: ScanWorkspace): Outcome? {
        val job = workspace.serverJob ?: return null
        val settings = CleanupServerSettings.load(context)
            ?: return Outcome.Failed(workspace, "The cleanup server access key is not set. Add it in Advanced settings.")
        val status = try {
            CleanupClient.status(settings, job.jobId)
        } catch (error: CleanupException) {
            if (error.jobGone) {
                workspace.serverJob = null
                workspace.serverJobError = error.message
                return Outcome.Failed(workspace, error.message.orEmpty())
            }
            throw error
        }
        return when {
            status.finished -> {
                val result = CleanupClient.result(settings, job.jobId)
                val piecesJson = workspace.serverPieces(job.jobId)
                val version = if (piecesJson != null) {
                    // Only doubtful sentences were sent: put the corrected ones back into the device-cleaned text.
                    val rules = OcrRulesParser.parse(OcrRulesStore.readRules(context)).takeIf { it.isValid }?.rules.orEmpty()
                    val device = workspace.ensureDeviceVersion(rules) ?: throw CleanupException("This book no longer has any text.")
                    val merged = AiReviewSelection.apply(workspace.chunks(device), AiReviewSelection.fromJson(piecesJson), result.chunks)
                    workspace.saveCompletedVersion(VersionKind.SERVER, result.model.ifBlank { job.model }, merged, result.seconds, device.rawSignature)
                } else {
                    workspace.saveCompletedVersion(VersionKind.SERVER, result.model.ifBlank { job.model }, result.chunks, result.seconds, job.rawSignature)
                }
                workspace.deleteServerPieces(job.jobId)
                CleanupClient.delete(settings, job.jobId)
                workspace.serverJob = null
                workspace.serverJobError = null
                Outcome.Finished(workspace, version)
            }
            status.failed -> {
                workspace.deleteServerPieces(job.jobId)
                CleanupClient.delete(settings, job.jobId)
                workspace.serverJob = null
                workspace.serverJobError = status.error ?: "The cleanup server could not finish this book."
                Outcome.Failed(workspace, workspace.serverJobError.orEmpty())
            }
            else -> Outcome.Running(workspace, status)
        }
    }

    /** Checks every pending job (used by the background worker). Blocking. */
    fun checkAll(context: Context): List<Outcome> = pendingWorkspaces(context).mapNotNull { workspace ->
        runCatching { check(context, workspace) }.getOrNull()
    }

    /** Background checks every 15 minutes while any job is pending (needs a network connection). */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CleanupCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelSchedule(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun notify(context: Context, outcome: Outcome) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "AI cleanup", NotificationManager.IMPORTANCE_DEFAULT))
        val title = BookExport.title(outcome.workspace)
        val (heading, text) = when (outcome) {
            is Outcome.Finished -> "Cleanup finished" to "$title: ${outcome.version.label} is ready to compare in Review."
            is Outcome.Failed -> "Cleanup stopped" to "$title: ${outcome.message}"
            is Outcome.Running -> return
        }
        val open = PendingIntent.getActivity(
            context,
            outcome.workspace.key.hashCode(),
            ReviewActivity.intent(context, outcome.workspace.key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(outcome.workspace.key.hashCode(), notification)
    }
}

/** Runs every 15 minutes while a cleanup job is pending; stops itself when none are left. */
class CleanupCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        CleanupSync.checkAll(applicationContext).forEach { CleanupSync.notify(applicationContext, it) }
        if (CleanupSync.pendingWorkspaces(applicationContext).isEmpty()) CleanupSync.cancelSchedule(applicationContext)
        Result.success()
    }
}
