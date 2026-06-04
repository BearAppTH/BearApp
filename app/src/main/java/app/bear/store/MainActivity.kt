package app.bear.store

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.bear.store.adapter.AppCardAdapter
import app.bear.store.databinding.ActivityMainBinding
import app.bear.store.model.AppItem
import app.bear.store.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: AppCardAdapter

    private val downloadedFiles = mutableMapOf<String, File>()
    private var pendingDownloadApp: AppItem? = null

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
            pendingDownloadApp?.let { beginDownload(it) }
        }
    }

    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshInstallStates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        observeViewModel()
        setupListeners()
        viewModel.fetchApps()
    }

    override fun onResume() {
        super.onResume()
        viewModel.resetCompletedDownloads()
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

        // Auto-install immediately when download finishes
        lifecycleScope.launch {
            viewModel.autoInstallTrigger.collect { app ->
                installApk(app)
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
        val destFile = File(getExternalFilesDir(null), fileName)
        downloadedFiles[app.id] = destFile
        viewModel.startDownload(app, destFile)
    }

    private fun installApk(app: AppItem) {
        val file = downloadedFiles[app.id] ?: return
        if (!file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "ไม่สามารถติดตั้งได้", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uninstallApp(app: AppItem) {
        if (app.packageName.isBlank()) return
        try {
            uninstallLauncher.launch(
                Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:${app.packageName}")
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "ไม่สามารถถอนการติดตั้งได้", Toast.LENGTH_SHORT).show()
        }
    }
}
