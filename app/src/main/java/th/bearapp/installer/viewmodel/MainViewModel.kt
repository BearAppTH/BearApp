package th.bearapp.installer.viewmodel

import android.app.Application
import android.app.DownloadManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import th.bearapp.installer.model.AppRelease
import th.bearapp.installer.network.GitHubApiService

enum class DownloadState {
    IDLE, LOADING, READY_TO_DOWNLOAD, DOWNLOADING, DOWNLOAD_COMPLETE, ERROR
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _releaseInfo = MutableLiveData<AppRelease?>()
    val releaseInfo: LiveData<AppRelease?> = _releaseInfo

    private val _downloadState = MutableLiveData(DownloadState.IDLE)
    val downloadState: LiveData<DownloadState> = _downloadState

    private val _downloadProgress = MutableLiveData(0)
    val downloadProgress: LiveData<Int> = _downloadProgress

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private var progressJob: Job? = null
    private val apiService = GitHubApiService()

    companion object {
        const val GITHUB_OWNER = "bearappth"
        const val GITHUB_REPO = "bearapp"
    }

    fun fetchLatestRelease() {
        _downloadState.value = DownloadState.LOADING
        _errorMessage.value = null
        _releaseInfo.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val release = apiService.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
                _releaseInfo.postValue(release)
                _downloadState.postValue(DownloadState.READY_TO_DOWNLOAD)
            } catch (e: Exception) {
                _errorMessage.postValue(e.message ?: "เกิดข้อผิดพลาดที่ไม่ทราบสาเหตุ")
                _downloadState.postValue(DownloadState.ERROR)
            }
        }
    }

    fun onDownloadStarted() {
        _downloadState.value = DownloadState.DOWNLOADING
        _downloadProgress.value = 0
    }

    fun trackDownload(downloadId: Long, downloadManager: DownloadManager) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val dlIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val downloaded = cursor.getLong(dlIdx)
                    val total = cursor.getLong(totalIdx)
                    val status = cursor.getInt(statusIdx)
                    if (total > 0) {
                        _downloadProgress.postValue(((downloaded * 100) / total).toInt())
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

    fun onDownloadComplete() {
        progressJob?.cancel()
        _downloadProgress.value = 100
        _downloadState.value = DownloadState.DOWNLOAD_COMPLETE
    }

    fun onDownloadFailed() {
        progressJob?.cancel()
        _errorMessage.value = "การดาวน์โหลดล้มเหลว กรุณาลองใหม่"
        _downloadState.value = DownloadState.ERROR
    }

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
    }
}
