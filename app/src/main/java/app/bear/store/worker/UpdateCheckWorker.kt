package app.bear.store.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.bear.store.InstallResultReceiver
import app.bear.store.MainActivity
import app.bear.store.PrefsManager
import app.bear.store.R
import app.bear.store.network.GitHubApiService
import app.bear.store.util.VersionUtils
import app.bear.store.util.ChecksumUtils
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val apiService = GitHubApiService(applicationContext)
            val config = apiService.getAppsConfig(GITHUB_OWNER, GITHUB_REPO)
            val jsonCache = mutableMapOf<String, JsonObject>()
            val apps = config.apps.map { app ->
                if (!app.isGitHubManaged) return@map app
                try {
                    val key = "${app.githubOwner}/${app.githubRepo}"
                    val releaseJson = jsonCache.getOrPut(key) {
                        apiService.fetchRelease(app.githubOwner, app.githubRepo)
                    }
                    val release = if (app.githubFilePrefix.isNotBlank()) {
                        apiService.parseAssetFromRelease(releaseJson, app.githubFilePrefix) ?: return@map app
                    } else {
                        apiService.parseRelease(releaseJson)
                    }
                    app.copy(
                        version = release.tagName.trimStart('v', 'V'),
                        downloadUrl = release.apkUrl ?: app.downloadUrl,
                        sha256Digest = release.apkDigest
                    )
                } catch (_: Exception) { app }
            }
            val pm = applicationContext.packageManager
            val updates = apps.filter { app ->
                if (app.packageName.isBlank() || app.version.isBlank()) return@filter false
                try {
                    @Suppress("DEPRECATION")
                    val info = pm.getPackageInfo(app.packageName, 0)
                    VersionUtils.isVersionNewer(app.version, info.versionName ?: "")
                } catch (_: PackageManager.NameNotFoundException) { false }
            }
            if (updates.isNotEmpty()) {
                val prefs = PrefsManager(applicationContext)
                if (prefs.autoUpdate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    autoDownloadAndInstall(updates)
                } else {
                    showUpdateNotification(updates.map { it.name })
                }
            }
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun autoDownloadAndInstall(updates: List<app.bear.store.model.AppItem>) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val packageInstaller = applicationContext.packageManager.packageInstaller
        val needsManualInstall = mutableListOf<String>()
        var committedCount = 0
        for (app in updates) {
            if (app.downloadUrl.isBlank()) { needsManualInstall.add(app.name); continue }
            val destFile = File(applicationContext.filesDir, "${app.id}-worker-update.apk")
            try {
                val response = client.newCall(Request.Builder().url(app.downloadUrl).build()).execute()
                if (!response.isSuccessful) { needsManualInstall.add(app.name); continue }
                response.body?.use { body ->
                    destFile.outputStream().use { body.byteStream().copyTo(it) }
                }
                if (!ChecksumUtils.verify(destFile, app.sha256Digest)) {
                    needsManualInstall.add(app.name)
                    continue
                }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val sessionId = try {
                    packageInstaller.createSession(params)
                } catch (_: SecurityException) {
                    // Not original installer — fall back to manual notification
                    needsManualInstall.add(app.name)
                    continue
                }
                packageInstaller.openSession(sessionId).use { session ->
                    destFile.inputStream().use { input ->
                        session.openWrite(destFile.name, 0, destFile.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    val intent = Intent(applicationContext, InstallResultReceiver::class.java).apply {
                        putExtra(InstallResultReceiver.EXTRA_APP_ID, app.id)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        applicationContext, sessionId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    session.commit(pendingIntent.intentSender)
                }
                committedCount++
            } catch (_: Exception) {
                needsManualInstall.add(app.name)
            } finally {
                destFile.delete()
            }
        }
        if (committedCount > 0) showAutoInstallNotification(committedCount)
        if (needsManualInstall.isNotEmpty()) showUpdateNotification(needsManualInstall)
    }

    private fun showAutoInstallNotification(count: Int) {
        val ctx = applicationContext
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bear_logo)
            .setContentTitle(ctx.getString(R.string.notif_auto_update_title))
            .setContentText(ctx.getString(R.string.notif_auto_update_message, count))
            .setAutoCancel(true)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(AUTO_UPDATE_NOTIF_ID, notification)
    }

    private fun showUpdateNotification(names: List<String>) {
        val ctx = applicationContext
        val contentText = if (names.size <= 2) {
            ctx.getString(R.string.notif_updates_named, names.joinToString(", "))
        } else {
            ctx.getString(R.string.notif_updates_message, names.size)
        }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_TRIGGER_AUTO_DOWNLOAD, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bear_logo)
            .setContentTitle(ctx.getString(R.string.notif_updates_title))
            .setContentText(contentText)
            .setNumber(names.size)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "bear_store_updates"
        const val NOTIFICATION_ID = 1001
        const val GITHUB_OWNER = "bearappth"
        const val GITHUB_REPO = "bearapp"
        const val EXTRA_TRIGGER_AUTO_DOWNLOAD = "trigger_auto_download"
        private const val AUTO_UPDATE_NOTIF_ID = 1003
    }
}
