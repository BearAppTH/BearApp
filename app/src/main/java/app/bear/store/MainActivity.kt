package app.bear.store

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
import androidx.recyclerview.widget.LinearLayoutManager
import app.bear.store.adapter.AppCardAdapter
import app.bear.store.databinding.ActivityMainBinding
import app.bear.store.model.AppItem
import app.bear.store.viewmodel.MainViewModel
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var adapter: AppCardAdapter

    private val activeDownloads = mutableMapOf<Long, String>()
    private val downloadedFiles = mutableMapOf<String, File>()

    private var pendingDownloadApp: AppItem? = null

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
            pendingDownloadApp?.let { beginDownload(it) }
        }
    }

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val appId = activeDownloads[id] ?: return
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cursor = dm.query(DownloadManager.Query().setFilterById(id))
            if (cursor.moveToFirst()) {
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (cursor.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL) {
                    viewModel.onDownloadComplete(appId)
                } else {
                    viewModel.onDownloadFailed(appId)
                }
            }
            cursor.close()
            activeDownloads.remove(id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        observeViewModel()
        setupListeners()

        registerDownloadReceiver()
        viewModel.fetchApps()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshInstallStates()
    }

    private fun setupRecyclerView() {
        adapter = AppCardAdapter(
            onDownload = { app -> checkPermissionAndDownload(app) },
            onInstall  = { app -> installApk(app) },
            onUninstall = { app -> uninstallApp(app) }
        )
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
    }

    private fun observeViewModel() {
        viewModel.apps.observe(this) { apps ->
            adapter.setApps(apps)
            binding.tvAppCount.text = "${apps.size} แอป"
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.shimmerLayout.visibility = if (loading) View.VISIBLE else View.GONE
            if (!loading) {
                binding.rvApps.visibility =
                    if (viewModel.errorMessage.value == null) View.VISIBLE else View.GONE
            }
        }

        viewModel.errorMessage.observe(this) { msg ->
            binding.errorLayout.visibility = if (msg != null) View.VISIBLE else View.GONE
            binding.rvApps.visibility = if (msg != null) View.GONE else View.VISIBLE
            binding.tvErrorMessage.text = msg ?: ""
        }

        viewModel.downloadStates.observe(this) { states ->
            states.forEach { (appId, pair) ->
                adapter.updateState(appId, pair.first, pair.second)
            }
        }

        viewModel.installStates.observe(this) { states ->
            states.forEach { (appId, pair) ->
                adapter.updateInstallState(appId, pair.first, pair.second)
            }
        }
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener { viewModel.fetchApps() }
        binding.btnRetry.setOnClickListener { viewModel.fetchApps() }
    }

    private fun checkPermissionAndDownload(app: AppItem) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingDownloadApp = app
            installPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            return
        }
        beginDownload(app)
    }

    private fun beginDownload(app: AppItem) {
        val fileName = "${app.id}-${app.version.ifBlank { "latest" }}.apk"
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val request = DownloadManager.Request(Uri.parse(app.downloadUrl)).apply {
            setTitle(app.name)
            setDescription("กำลังดาวน์โหลด ${app.name}...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType("application/vnd.android.package-archive")
        }

        val downloadId = dm.enqueue(request)
        activeDownloads[downloadId] = app.id
        downloadedFiles[app.id] = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )

        viewModel.onDownloadStarted(app.id)
        viewModel.trackDownload(app.id, downloadId, dm)
    }

    private fun installApk(app: AppItem) {
        val file = downloadedFiles[app.id] ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun uninstallApp(app: AppItem) {
        if (app.packageName.isBlank()) return
        startActivity(Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${app.packageName}")
        })
    }

    private fun registerDownloadReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                downloadCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadCompleteReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(downloadCompleteReceiver) } catch (_: Exception) {}
    }
}
