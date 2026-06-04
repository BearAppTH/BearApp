package app.bear.store.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import app.bear.store.BuildConfig
import app.bear.store.R
import app.bear.store.adapter.CardDownloadState
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

    private val _downloadStates = MutableLiveData<Map<String, Pair<CardDownloadState, Int>>>(emptyMap())
    val downloadStates: LiveData<Map<String, Pair<CardDownloadState, Int>>> = _downloadStates

    private val _installStates = MutableLiveData<Map<String, Pair<InstallState, String?>>>(emptyMap())
    val installStates: LiveData<Map<String, Pair<InstallState, String?>>> = _installStates

    private val _autoInstallTrigger = MutableSharedFlow<AppItem>(extraBufferCapacity = 10)
    val autoInstallTrigger = _autoInstallTrigger.asSharedFlow()

    private val _selfUpdateState = MutableLiveData<SelfUpdateState>(SelfUpdateState.Checking)
    val selfUpdateState: LiveData<SelfUpdateState> = _selfUpdateState

    private val _selfUpdateInstallTrigger = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val selfUpdateInstallTrigger = _selfUpdateInstallTrigger.asSharedFlow()

    private val progressJobs = mutableMapOf<String, Job>()
    private val apiService = GitHubApiService()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    companion object {
        const val GITHUB_OWNER = "bearappth"
        const val GITHUB_REPO = "bearapp"
    }

    private fun str(id: Int) = getApplication<Application>().getString(id)

    fun fetchApps() {
        _isLoading.value = true
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = apiService.getAppsConfig(GITHUB_OWNER, GITHUB_REPO)
                _apps.postValue(config.apps)
                checkAllInstallStates(config.apps)
            } catch (e: Exception) {
                _errorMessage.postValue(e.message ?: str(R.string.error_unknown))
            } finally {
                _isLoading.postValue(false)
            }
        }
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
                isVersionNewer(app.version, installedVersion ?: "") -> InstallState.UPDATE_AVAILABLE
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
        updateDownloadState(app.id, CardDownloadState.DOWNLOADING, 0)
        progressJobs[app.id] = viewModelScope.launch(Dispatchers.IO) {
            val call = downloadClient.newCall(Request.Builder().url(app.downloadUrl).build())
            try {
                val response = call.execute()
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body ?: throw Exception(str(R.string.error_no_data))
                val total = body.contentLength()
                var downloaded = 0L
                destFile.parentFile?.mkdirs()
                destFile.outputStream().use { out ->
                    body.byteStream().use { inp ->
                        val buf = ByteArray(8 * 1024)
                        var n = 0
                        while (isActive && inp.read(buf).also { n = it } >= 0) {
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                updateDownloadState(
                                    app.id, CardDownloadState.DOWNLOADING,
                                    ((downloaded * 100) / total).toInt()
                                )
                            }
                        }
                    }
                }
                if (isActive) {
                    updateDownloadState(app.id, CardDownloadState.COMPLETE, 100)
                    _autoInstallTrigger.emit(app)
                } else {
                    destFile.delete()
                    updateDownloadState(app.id, CardDownloadState.IDLE, 0)
                }
            } catch (e: Exception) {
                call.cancel()
                destFile.delete()
                updateDownloadState(app.id, CardDownloadState.ERROR, 0)
            }
        }
    }

    fun cancelDownload(appId: String) {
        progressJobs[appId]?.cancel()
        progressJobs.remove(appId)
    }

    fun checkSelfUpdate() {
        _selfUpdateState.postValue(SelfUpdateState.Checking)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val release = apiService.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
                val latestCode = release.tagName.split(".")
                    .lastOrNull()?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                val currentCode = BuildConfig.VERSION_CODE
                if (latestCode > currentCode && release.apkUrl != null) {
                    _selfUpdateState.postValue(
                        SelfUpdateState.UpdateAvailable(release.tagName, release.apkUrl)
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

    fun startSelfUpdate(downloadUrl: String, destFile: File) {
        _selfUpdateState.postValue(SelfUpdateState.Downloading(0))
        viewModelScope.launch(Dispatchers.IO) {
            val call = downloadClient.newCall(Request.Builder().url(downloadUrl).build())
            try {
                val response = call.execute()
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body ?: throw Exception(str(R.string.error_no_data))
                val total = body.contentLength()
                var downloaded = 0L
                destFile.parentFile?.mkdirs()
                destFile.outputStream().use { out ->
                    body.byteStream().use { inp ->
                        val buf = ByteArray(8 * 1024)
                        var n = 0
                        while (isActive && inp.read(buf).also { n = it } >= 0) {
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                _selfUpdateState.postValue(
                                    SelfUpdateState.Downloading(((downloaded * 100) / total).toInt())
                                )
                            }
                        }
                    }
                }
                if (isActive) {
                    _selfUpdateState.postValue(SelfUpdateState.ReadyToInstall)
                    _selfUpdateInstallTrigger.emit(destFile)
                } else {
                    destFile.delete()
                    _selfUpdateState.postValue(SelfUpdateState.Error(str(R.string.error_download_cancelled)))
                }
            } catch (e: Exception) {
                call.cancel()
                destFile.delete()
                _selfUpdateState.postValue(
                    SelfUpdateState.Error(e.message ?: str(R.string.error_download_failed))
                )
            }
        }
    }

    fun getApp(appId: String): AppItem? = _apps.value?.find { it.id == appId }

    fun resetCompletedDownloads() {
        val current = _downloadStates.value.orEmpty()
        val hasCompleted = current.values.any { it.first == CardDownloadState.COMPLETE }
        if (!hasCompleted) return
        _downloadStates.postValue(
            current.mapValues { (_, v) ->
                if (v.first == CardDownloadState.COMPLETE) Pair(CardDownloadState.IDLE, 0) else v
            }
        )
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

    private fun updateDownloadState(appId: String, state: CardDownloadState, progress: Int) {
        val current = _downloadStates.value.orEmpty().toMutableMap()
        current[appId] = Pair(state, progress)
        _downloadStates.postValue(current)
    }

    override fun onCleared() {
        super.onCleared()
        progressJobs.values.forEach { it.cancel() }
    }
}
