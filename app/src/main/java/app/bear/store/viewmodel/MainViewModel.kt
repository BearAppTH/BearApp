package app.bear.store.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import app.bear.store.util.VersionUtils
import android.app.NotificationManager
import android.content.Context
import app.bear.store.PrefsManager
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.os.StatFs
import okhttp3.OkHttpClient
import okhttp3.Request
import app.bear.store.BuildConfig
import app.bear.store.R
import app.bear.store.adapter.CardDownloadState
import app.bear.store.adapter.DownloadInfo
import app.bear.store.model.AppItem
import app.bear.store.model.InstallState
import app.bear.store.model.SelfUpdateState
import app.bear.store.network.GitHubApiService
import java.io.File
import java.util.concurrent.TimeUnit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _apps = MutableLiveData<List<AppItem>>()
    val apps: LiveData<List<AppItem>> = _apps

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _isUsingCache = MutableLiveData(false)
    val isUsingCache: LiveData<Boolean> = _isUsingCache

    private val _downloadStates = MutableLiveData<Map<String, DownloadInfo>>(emptyMap())
    val downloadStates: LiveData<Map<String, DownloadInfo>> = _downloadStates

    private val _installStates = MutableLiveData<Map<String, Pair<InstallState, String?>>>(emptyMap())
    val installStates: LiveData<Map<String, Pair<InstallState, String?>>> = _installStates

    private val _autoInstallTrigger = MutableSharedFlow<AppItem>(extraBufferCapacity = 10)
    val autoInstallTrigger = _autoInstallTrigger.asSharedFlow()

    private val _selfUpdateState = MutableLiveData<SelfUpdateState>(SelfUpdateState.Checking)
    val selfUpdateState: LiveData<SelfUpdateState> = _selfUpdateState

    private val _selfUpdateInstallTrigger = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val selfUpdateInstallTrigger = _selfUpdateInstallTrigger.asSharedFlow()

    private val progressJobs = mutableMapOf<String, Job>()
    private var selfUpdateJob: Job? = null
    private val apiService = GitHubApiService(getApplication())
    private val gson = Gson()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val notificationManager =
        getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val GITHUB_OWNER = "bearappth"
        const val GITHUB_REPO = "bearapp"
        const val DOWNLOAD_CHANNEL_ID = "bear_store_downloads"
        private const val SELF_UPDATE_NOTIF_ID = 1002
        private const val CACHE_FILE = "apps_cache.json"
        private const val CACHE_VERSION = 2
        private const val CACHE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    }

    private data class AppsCacheEnvelope(val v: Int, val savedAt: Long, val apps: List<AppItem>)

    private fun str(id: Int) = getApplication<Application>().getString(id)

    fun fetchApps() {
        _isLoading.value = true
        _errorMessage.value = null
        _isUsingCache.value = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = apiService.getAppsConfig(GITHUB_OWNER, GITHUB_REPO)
                val apps = resolveGitHubApps(config.apps)
                saveAppsCache(apps)
                _apps.postValue(apps)
                checkAllInstallStates(apps)
            } catch (e: Exception) {
                loadAppsCache()?.let { cached ->
                    _apps.postValue(cached)
                    checkAllInstallStates(cached)
                    _isUsingCache.postValue(true)
                } ?: _errorMessage.postValue(e.message ?: str(R.string.error_unknown))
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    private fun resolveGitHubApps(apps: List<AppItem>): List<AppItem> {
        // Cache release JSON per "owner/repo" to avoid hitting the same endpoint multiple times
        val jsonCache = mutableMapOf<String, JsonObject>()
        return apps.map { app ->
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
                    updatedAt = toThaiDate(release.publishedAt).ifBlank { app.updatedAt },
                    changelog = release.body.ifBlank { app.changelog },
                    downloadSize = release.apkSize
                )
            } catch (_: Exception) { app }
        }
    }

    private fun toThaiDate(isoDate: String): String {
        val datePart = isoDate.take(10).split("-")
        if (datePart.size != 3) return ""
        val isThai = PrefsManager(getApplication()).language == PrefsManager.LANG_TH
        val year = datePart[0].toIntOrNull()?.let { if (isThai) it + 543 else it } ?: return ""
        return "$year-${datePart[1]}-${datePart[2]}"
    }

    fun refreshInstallStates() {
        val apps = _apps.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            checkAllInstallStates(apps)
        }
    }

    private fun checkAllInstallStates(apps: List<AppItem>) {
        val pm = getApplication<Application>().packageManager
        val states = apps.associate { app ->
            val (isInstalled, installedVersion) = getInstalledInfo(pm, app.packageName)
            val installState = when {
                !isInstalled -> InstallState.NOT_INSTALLED
                VersionUtils.isVersionNewer(app.version, installedVersion ?: "") -> InstallState.UPDATE_AVAILABLE
                else -> InstallState.INSTALLED_UP_TO_DATE
            }
            app.id to Pair(installState, installedVersion)
        }
        _installStates.postValue(states)
    }

    private fun getInstalledInfo(pm: PackageManager, packageName: String): Pair<Boolean, String?> {
        if (packageName.isBlank()) return Pair(false, null)
        return try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, 0)
            Pair(true, info.versionName)
        } catch (e: PackageManager.NameNotFoundException) {
            Pair(false, null)
        }
    }

    fun startDownload(app: AppItem, destFile: File) {
        if (progressJobs[app.id]?.isActive == true) return
        val notifId = app.id.hashCode()
        updateDownloadState(app.id, CardDownloadState.DOWNLOADING, 0)
        postDownloadNotification(app.name, notifId, 0, 0L)
        progressJobs[app.id] = viewModelScope.launch(Dispatchers.IO) {
            val call = downloadClient.newCall(Request.Builder().url(app.downloadUrl).build())
            try {
                val response = call.execute()
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body ?: throw Exception(str(R.string.error_no_data))
                val total = body.contentLength()
                var downloaded = 0L
                var lastPct = -1
                destFile.parentFile?.mkdirs()
                if (total > 0) {
                    val statsPath = destFile.parentFile?.path
                        ?: getApplication<Application>().filesDir.path
                    val stats = StatFs(statsPath)
                    val available = stats.availableBlocksLong * stats.blockSizeLong
                    if (available < total) throw Exception(str(R.string.error_insufficient_space))
                }
                destFile.outputStream().use { out ->
                    body.byteStream().use { inp ->
                        val buf = ByteArray(8 * 1024)
                        var n = 0
                        while (isActive && inp.read(buf).also { n = it } >= 0) {
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt()
                                if (pct != lastPct) {
                                    updateDownloadState(app.id, CardDownloadState.DOWNLOADING, pct, downloadedBytes = downloaded, totalBytes = total)
                                    postDownloadNotification(app.name, notifId, pct, total)
                                    lastPct = pct
                                }
                            }
                        }
                    }
                }
                if (isActive) {
                    cancelDownloadNotification(notifId)
                    updateDownloadState(app.id, CardDownloadState.COMPLETE, 100)
                    _autoInstallTrigger.emit(app)
                } else {
                    destFile.delete()
                    cancelDownloadNotification(notifId)
                    updateDownloadState(app.id, CardDownloadState.IDLE, 0)
                }
            } catch (e: Exception) {
                call.cancel()
                destFile.delete()
                cancelDownloadNotification(notifId)
                updateDownloadState(app.id, CardDownloadState.ERROR, 0, e.message ?: str(R.string.error_download_failed))
            }
        }
    }

    fun cancelDownload(appId: String) {
        progressJobs[appId]?.cancel()
        progressJobs.remove(appId)
        cancelDownloadNotification(appId.hashCode())
        updateDownloadState(appId, CardDownloadState.IDLE, 0)
    }

    fun checkSelfUpdate() {
        _selfUpdateState.value = SelfUpdateState.Checking
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val release = apiService.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
                val latestVersion = release.tagName.trimStart('v', 'V')
                val currentVersion = BuildConfig.VERSION_NAME
                if (VersionUtils.isVersionNewer(latestVersion, currentVersion) && release.apkUrl != null) {
                    _selfUpdateState.postValue(
                        SelfUpdateState.UpdateAvailable(release.tagName, release.apkUrl, release.body)
                    )
                } else {
                    _selfUpdateState.postValue(SelfUpdateState.UpToDate)
                }
            } catch (e: Exception) {
                _selfUpdateState.postValue(
                    SelfUpdateState.Error(e.message ?: str(R.string.error_check_failed))
                )
            }
        }
    }

    fun cancelSelfUpdate() {
        selfUpdateJob?.cancel()
        selfUpdateJob = null
        cancelDownloadNotification(SELF_UPDATE_NOTIF_ID)
        _selfUpdateState.value = SelfUpdateState.Error(str(R.string.error_download_cancelled))
    }

    fun startSelfUpdate(downloadUrl: String, destFile: File) {
        _selfUpdateState.value = SelfUpdateState.Downloading(0)
        val appName = str(R.string.app_display_name)
        postDownloadNotification(appName, SELF_UPDATE_NOTIF_ID, 0, 0L)
        selfUpdateJob = viewModelScope.launch(Dispatchers.IO) {
            val call = downloadClient.newCall(Request.Builder().url(downloadUrl).build())
            try {
                val response = call.execute()
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body ?: throw Exception(str(R.string.error_no_data))
                val total = body.contentLength()
                var downloaded = 0L
                var lastPct = -1
                destFile.parentFile?.mkdirs()
                if (total > 0) {
                    val statsPath = destFile.parentFile?.path
                        ?: getApplication<Application>().filesDir.path
                    val stats = StatFs(statsPath)
                    val available = stats.availableBlocksLong * stats.blockSizeLong
                    if (available < total) throw Exception(str(R.string.error_insufficient_space))
                }
                destFile.outputStream().use { out ->
                    body.byteStream().use { inp ->
                        val buf = ByteArray(8 * 1024)
                        var n = 0
                        while (isActive && inp.read(buf).also { n = it } >= 0) {
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt()
                                if (pct != lastPct) {
                                    _selfUpdateState.postValue(SelfUpdateState.Downloading(pct))
                                    postDownloadNotification(appName, SELF_UPDATE_NOTIF_ID, pct, total)
                                    lastPct = pct
                                }
                            }
                        }
                    }
                }
                if (isActive) {
                    cancelDownloadNotification(SELF_UPDATE_NOTIF_ID)
                    _selfUpdateState.postValue(SelfUpdateState.ReadyToInstall)
                    _selfUpdateInstallTrigger.emit(destFile)
                } else {
                    destFile.delete()
                    cancelDownloadNotification(SELF_UPDATE_NOTIF_ID)
                    _selfUpdateState.postValue(SelfUpdateState.Error(str(R.string.error_download_cancelled)))
                }
            } catch (e: Exception) {
                call.cancel()
                destFile.delete()
                cancelDownloadNotification(SELF_UPDATE_NOTIF_ID)
                _selfUpdateState.postValue(
                    SelfUpdateState.Error(e.message ?: str(R.string.error_download_failed))
                )
            }
        }
    }

    fun getApp(appId: String): AppItem? = _apps.value?.find { it.id == appId }

    fun resetCompletedDownloads(keepIds: Set<String> = emptySet()) {
        val current = _downloadStates.value.orEmpty()
        val hasCompleted = current.values.any { it.state == CardDownloadState.COMPLETE }
        if (!hasCompleted) return
        _downloadStates.postValue(
            current.mapValues { (id, v) ->
                if (v.state == CardDownloadState.COMPLETE && id !in keepIds)
                    DownloadInfo(CardDownloadState.IDLE)
                else v
            }
        )
    }

    fun clearDownloadState(appId: String) {
        val current = _downloadStates.value.orEmpty().toMutableMap()
        if (current[appId]?.state == CardDownloadState.COMPLETE) {
            current[appId] = DownloadInfo(CardDownloadState.IDLE)
            _downloadStates.postValue(current)
        }
    }

    private fun postDownloadNotification(title: String, notifId: Int, progress: Int, total: Long) {
        val app = getApplication<Application>()
        val notification = NotificationCompat.Builder(app, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bear_logo)
            .setContentTitle(title)
            .setContentText(app.getString(R.string.notif_downloading, progress))
            .setProgress(100, progress, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        notificationManager.notify(notifId, notification)
    }

    private fun cancelDownloadNotification(notifId: Int) {
        notificationManager.cancel(notifId)
    }

    private fun saveAppsCache(apps: List<AppItem>) {
        try {
            val envelope = AppsCacheEnvelope(CACHE_VERSION, System.currentTimeMillis(), apps)
            File(getApplication<Application>().filesDir, CACHE_FILE)
                .writeText(gson.toJson(envelope))
        } catch (_: Exception) {}
    }

    private fun loadAppsCache(): List<AppItem>? {
        return try {
            val file = File(getApplication<Application>().filesDir, CACHE_FILE)
            if (!file.exists()) return null
            val envelope = gson.fromJson(file.readText(), AppsCacheEnvelope::class.java)
            val age = System.currentTimeMillis() - envelope.savedAt
            if (envelope.v != CACHE_VERSION || age > CACHE_MAX_AGE_MS) {
                file.delete()
                return null
            }
            envelope.apps
        } catch (_: Exception) { null }
    }

    private fun updateDownloadState(appId: String, state: CardDownloadState, progress: Int, error: String? = null, downloadedBytes: Long = 0L, totalBytes: Long = 0L) {
        val current = _downloadStates.value.orEmpty().toMutableMap()
        current[appId] = DownloadInfo(state, progress, error, downloadedBytes, totalBytes)
        _downloadStates.postValue(current)
    }

    override fun onCleared() {
        super.onCleared()
        progressJobs.keys.forEach { cancelDownloadNotification(it.hashCode()) }
        cancelDownloadNotification(SELF_UPDATE_NOTIF_ID)
        progressJobs.values.forEach { it.cancel() }
        selfUpdateJob?.cancel()
    }
}
