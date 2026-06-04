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

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val config = GitHubApiService(applicationContext).getAppsConfig(GITHUB_OWNER, GITHUB_REPO)
            val pm = applicationContext.packageManager
            val updates = config.apps.filter { app ->
                if (app.packageName.isBlank()) return@filter false
                try {
                    @Suppress("DEPRECATION")
                    val info = pm.getPackageInfo(app.packageName, 0)
                    isVersionNewer(app.version, info.versionName ?: "")
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

    private fun isVersionNewer(configVersion: String, installedVersion: String): Boolean {
        if (configVersion.isBlank() || installedVersion.isBlank()) return false
        val newParts = configVersion.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val insParts = installedVersion.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        if (newParts.isEmpty() || insParts.isEmpty()) return false
        for (i in 0 until maxOf(newParts.size, insParts.size)) {
            val n = newParts.getOrElse(i) { 0 }
            val ins = insParts.getOrElse(i) { 0 }
            if (n > ins) return true
            if (n < ins) return false
        }
        return false
    }

    companion object {
        const val CHANNEL_ID = "bear_store_updates"
        const val NOTIFICATION_ID = 1001
        const val GITHUB_OWNER = "bearappth"
        const val GITHUB_REPO = "bearapp"
    }
}
