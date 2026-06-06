package app.bear.store

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.bear.store.viewmodel.MainViewModel
import app.bear.store.worker.UpdateCheckWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow

class BearApplication : Application() {

    val pmInstallEvents = MutableSharedFlow<Pair<String?, Boolean>>(extraBufferCapacity = 10)

    override fun attachBaseContext(base: Context) {
        val lang = base.getSharedPreferences("bear_prefs", Context.MODE_PRIVATE)
            .getString("lang", PrefsManager.LANG_TH) ?: PrefsManager.LANG_TH
        super.attachBaseContext(LocaleHelper.applyLocale(base, lang))
    }

    override fun onCreate() {
        super.onCreate()
        PrefsManager(this).applyTheme()
        createNotificationChannel()
        scheduleUpdateCheck()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(
                UpdateCheckWorker.CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = getString(R.string.notif_channel_desc) })
            nm.createNotificationChannel(NotificationChannel(
                MainViewModel.DOWNLOAD_CHANNEL_ID,
                getString(R.string.notif_channel_download_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_download_desc) })
        }
    }

    private fun scheduleUpdateCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "bear_update_check",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }
}
