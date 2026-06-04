package th.bearapp.installer

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import th.bearapp.installer.databinding.ActivityMainBinding
import th.bearapp.installer.viewmodel.DownloadState
import th.bearapp.installer.viewmodel.MainViewModel
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var downloadId: Long = -1L
    private var downloadedApkFile: File? = null

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                beginDownload()
            }
        }
    }

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != downloadId) return
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
            if (cursor.moveToFirst()) {
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (cursor.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL) {
                    viewModel.onDownloadComplete()
                } else {
                    viewModel.onDownloadFailed()
                }
            }
            cursor.close()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        observeViewModel()
        setupListeners()

        viewModel.fetchLatestRelease()
    }

    private fun observeViewModel() {
        viewModel.releaseInfo.observe(this) { release ->
            release ?: return@observe
            binding.tvVersion.text = "เวอร์ชัน ${release.tagName}"
            binding.tvReleaseName.text = release.name
            binding.tvReleaseBody.text = release.body.ifBlank { "ไม่มีรายละเอียดการอัปเดต" }
            binding.tvPublishedDate.text = if (release.publishedAt.isNotBlank())
                "เผยแพร่: ${release.publishedAt}" else ""
            binding.tvNoApk.visibility =
                if (release.apkUrl == null) View.VISIBLE else View.GONE
        }

        viewModel.downloadState.observe(this) { state ->
            when (state) {
                DownloadState.IDLE -> setUiState(loading = false, downloading = false, done = false, error = false)
                DownloadState.LOADING -> setUiState(loading = true, downloading = false, done = false, error = false)
                DownloadState.READY_TO_DOWNLOAD -> setUiState(loading = false, downloading = false, done = false, error = false)
                DownloadState.DOWNLOADING -> setUiState(loading = false, downloading = true, done = false, error = false)
                DownloadState.DOWNLOAD_COMPLETE -> setUiState(loading = false, downloading = false, done = true, error = false)
                DownloadState.ERROR -> setUiState(loading = false, downloading = false, done = false, error = true)
            }
        }

        viewModel.downloadProgress.observe(this) { progress ->
            binding.progressBar.progress = progress
            binding.tvProgressPercent.text = "$progress%"
        }

        viewModel.errorMessage.observe(this) { msg ->
            binding.tvErrorMessage.text = msg ?: ""
            binding.tvErrorMessage.visibility = if (msg != null) View.VISIBLE else View.GONE
        }
    }

    private fun setupListeners() {
        binding.btnDownload.setOnClickListener { checkPermissionAndDownload() }
        binding.btnInstall.setOnClickListener { installDownloadedApk() }
        binding.btnRetry.setOnClickListener { viewModel.fetchLatestRelease() }
    }

    private fun checkPermissionAndDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                installPermissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
                return
            }
        }
        beginDownload()
    }

    private fun beginDownload() {
        val release = viewModel.releaseInfo.value ?: return
        val apkUrl = release.apkUrl ?: return
        val fileName = "bearapp-${release.tagName}.apk"

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("BearApp ${release.tagName}")
            setDescription("กำลังดาวน์โหลด...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType("application/vnd.android.package-archive")
        }

        downloadId = dm.enqueue(request)
        downloadedApkFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )

        viewModel.onDownloadStarted()
        viewModel.trackDownload(downloadId, dm)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installDownloadedApk() {
        val file = downloadedApkFile ?: return
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, "$packageName.provider", file)
        } else {
            @Suppress("DEPRECATION")
            Uri.fromFile(file)
        }
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun setUiState(loading: Boolean, downloading: Boolean, done: Boolean, error: Boolean) {
        binding.shimmerLayout.visibility = if (loading) View.VISIBLE else View.GONE
        binding.contentLayout.visibility = if (loading) View.GONE else View.VISIBLE

        binding.progressContainer.visibility = if (downloading) View.VISIBLE else View.GONE
        binding.btnDownload.visibility =
            if (!loading && !downloading && !done && viewModel.releaseInfo.value?.apkUrl != null) View.VISIBLE else View.GONE
        binding.btnInstall.visibility = if (done) View.VISIBLE else View.GONE
        binding.btnRetry.visibility = if (error) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(downloadCompleteReceiver) } catch (_: Exception) {}
    }
}
