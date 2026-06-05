package app.bear.store.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.bear.store.MainActivity
import app.bear.store.R
import app.bear.store.network.GitHubApiService
import app.bear.store.util.VersionUtils
import com.google.gson.JsonObject

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val apiService = GitHubApiService(applicationContext)
            val config = apiService.getAppsConfig(GITHUB_OWNER, GITHUB_REPO)
            // Resolve actual versions from GitHub releases before comparing
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
                    app.copy(version = release.tagName.trimStart('v', 'V'))
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
            if (updates.isNotEmpty()) showUpdateNotification(updates.size)
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    private fun showUpdateNotification(count: Int) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bear_logo)
            .setContentTitle(applicationContext.getString(R.string.notif_updates_title))
            .setContentText(applicationContext.getString(R.string.notif_updates_message, count))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "bear_store_updates"
        const val NOTIFICATION_ID = 1001
        const val GITHUB_OWNER = "bearappth"
        const val GITHUB_REPO = "bearapp"
    }
}
