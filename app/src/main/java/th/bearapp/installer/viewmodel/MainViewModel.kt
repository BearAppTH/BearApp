package th.bearapp.installer.viewmodel

import android.app.Application
import android.app.DownloadManager
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import th.bearapp.installer.adapter.CardDownloadState
import th.bearapp.installer.model.AppItem
import th.bearapp.installer.model.InstallState
import th.bearapp.installer.network.GitHubApiService

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

    private val progressJobs = mutableMapOf<String, Job>()
    private val apiService = GitHubApiService()

    companion object {
        const val GITHUB_OWNER = "bearappth"
        const val GITHUB_REPO = "bearapp"
    }

    fun fetchApps() {
        _isLoading.value = true
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = apiService.getAppsConfig(GITHUB_OWNER, GITHUB_REPO)
                _apps.postValue(config.apps)
                checkAllInstallStates(config.apps)
            } catch (e: Exception) {
                _errorMessage.postValue(e.message ?: "เกิดข้อผิดพลาดที่ไม่ทราบสาเหตุ")
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
            val installedVersion: String? = if (app.packageName.isNotBlank()) {
                try {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(app.packageName, 0).versionName
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            } else null

            val installState = when {
                installedVersion == null -> InstallState.NOT_INSTALLED
                isVersionNewer(app.version, installedVersion) -> InstallState.UPDATE_AVAILABLE
                else -> InstallState.INSTALLED_UP_TO_DATE
            }
            app.id to Pair(installState, installedVersion)
        }
        _installStates.postValue(states)
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

    fun onDownloadStarted(appId: String) {
        updateDownloadState(appId, CardDownloadState.DOWNLOADING, 0)
    }

    fun trackDownload(appId: String, downloadId: Long, downloadManager: DownloadManager) {
        progressJobs[appId]?.cancel()
        progressJobs[appId] = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                if (cursor.moveToFirst()) {
                    val dlIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val downloaded = cursor.getLong(dlIdx)
                    val total = cursor.getLong(totalIdx)
                    val status = cursor.getInt(statusIdx)
                    if (total > 0) {
                        updateDownloadState(appId, CardDownloadState.DOWNLOADING, ((downloaded * 100) / total).toInt())
                    }
                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                        cursor.close()
                        break
                    }
                }
                cursor.close()
                delay(300)
            }
        }
    }

    fun onDownloadComplete(appId: String) {
        progressJobs[appId]?.cancel()
        updateDownloadState(appId, CardDownloadState.COMPLETE, 100)
    }

    fun onDownloadFailed(appId: String) {
        progressJobs[appId]?.cancel()
        updateDownloadState(appId, CardDownloadState.ERROR, 0)
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
