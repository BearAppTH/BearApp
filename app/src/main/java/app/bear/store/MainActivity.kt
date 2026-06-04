package app.bear.store

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.bear.store.adapter.AppCardAdapter
import app.bear.store.adapter.ChipFilter
import app.bear.store.databinding.ActivityMainBinding
import app.bear.store.databinding.DialogAboutBinding
import app.bear.store.databinding.DialogAppDetailBinding
import app.bear.store.databinding.DialogSettingsBinding
import app.bear.store.model.AppItem
import app.bear.store.model.InstallState
import app.bear.store.model.SelfUpdateState
import app.bear.store.viewmodel.MainViewModel
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
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
    ) { result ->
        viewModel.refreshInstallStates()
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, R.string.toast_uninstall_success, Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied silently */ }

    // ─── Locale ──────────────────────────────────────────────────────────────

    override fun attachBaseContext(newBase: Context) {
        val lang = PrefsManager(newBase).language
        super.attachBaseContext(LocaleHelper.applyLocale(newBase, lang))
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        observeViewModel()
        setupListeners()
        requestNotificationPermission()
        // Skip re-fetch on theme/language recreate to avoid state flash
        if (viewModel.apps.value.isNullOrEmpty()) {
            viewModel.fetchApps()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.resetCompletedDownloads()
        viewModel.refreshInstallStates()
    }

    // ─── Setup ───────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_about    -> { showAboutDialog(); true }
                R.id.menu_settings -> { showSettingsDialog(); true }
                else               -> false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = AppCardAdapter(
            onDownload  = { app -> checkPermissionAndDownload(app) },
            onInstall   = { app -> installApk(app) },
            onUninstall = { app -> uninstallApp(app) },
            onCancel    = { app -> viewModel.cancelDownload(app.id) },
            onDetails   = { app -> showAppDetailDialog(app) }
        )
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter

        adapter.onFilterEmpty = { isEmpty ->
            binding.tvSearchEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            if (viewModel.isLoading.value != true && viewModel.errorMessage.value == null) {
                binding.rvApps.visibility = if (isEmpty) View.GONE else View.VISIBLE
            }
        }
    }

    private fun observeViewModel() {
        viewModel.apps.observe(this) { apps ->
            adapter.setApps(apps)
            // Re-apply install states immediately so there's no flash of wrong buttons
            viewModel.installStates.value?.forEach { (appId, pair) ->
                adapter.updateInstallState(appId, pair.first, pair.second)
            }
            binding.tvAppCount.text = getString(R.string.app_count_format, apps.size)
        }

        viewModel.isLoading.observe(this) { loading ->
            if (!loading) binding.swipeRefresh.isRefreshing = false
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

        viewModel.isUsingCache.observe(this) { isCache ->
            if (isCache) {
                Snackbar.make(binding.root, R.string.toast_cached_data, Snackbar.LENGTH_LONG).show()
            }
        }

        viewModel.downloadStates.observe(this) { states ->
            states.forEach { (appId, info) ->
                adapter.updateState(appId, info.state, info.progress, info.error)
            }
        }

        viewModel.installStates.observe(this) { states ->
            states.forEach { (appId, pair) ->
                adapter.updateInstallState(appId, pair.first, pair.second)
            }
            val updateCount = states.values.count { it.first == InstallState.UPDATE_AVAILABLE }
            if (updateCount >= 2) {
                binding.btnUpdateAll.text = getString(R.string.btn_update_all, updateCount)
                binding.btnUpdateAll.visibility = View.VISIBLE
            } else {
                binding.btnUpdateAll.visibility = View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.autoInstallTrigger.collect { app -> installApk(app) }
        }

        lifecycleScope.launch {
            viewModel.selfUpdateInstallTrigger.collect { file -> installApkFromFile(file) }
        }
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener { viewModel.fetchApps() }
        binding.btnRetry.setOnClickListener { viewModel.fetchApps() }

        binding.swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.primary))
        binding.swipeRefresh.setOnRefreshListener { viewModel.fetchApps() }

        binding.etSearch.addTextChangedListener { text ->
            val query = text?.toString() ?: ""
            adapter.filter(query)
            if (query.isBlank()) {
                binding.tvSearchEmpty.visibility = View.GONE
                binding.rvApps.visibility = if (viewModel.errorMessage.value == null) View.VISIBLE else View.GONE
            }
        }

        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when {
                R.id.chipUpdates in checkedIds -> ChipFilter.UPDATES
                R.id.chipInstalled in checkedIds -> ChipFilter.INSTALLED
                else -> ChipFilter.ALL
            }
            adapter.setChipFilter(filter)
        }

        binding.btnUpdateAll.setOnClickListener {
            val apps = viewModel.apps.value ?: return@setOnClickListener
            apps.filter { app ->
                viewModel.installStates.value?.get(app.id)?.first == InstallState.UPDATE_AVAILABLE
                        && app.hasDownloadUrl
            }.forEach { app -> checkPermissionAndDownload(app) }
        }
    }

    // ─── Settings dialog ─────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        val prefs = PrefsManager(this)
        val b = DialogSettingsBinding.inflate(LayoutInflater.from(this))

        when (prefs.themeMode) {
            PrefsManager.THEME_LIGHT -> b.rbThemeLight.isChecked = true
            PrefsManager.THEME_DARK  -> b.rbThemeDark.isChecked = true
            else                     -> b.rbThemeSystem.isChecked = true
        }
        if (prefs.language == PrefsManager.LANG_EN) b.rbLangEn.isChecked = true
        else b.rbLangTh.isChecked = true

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(b.root)
            .setPositiveButton(R.string.btn_close, null)
            .create()

        dialog.show()

        b.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val newTheme = when (checkedId) {
                R.id.rbThemeLight -> PrefsManager.THEME_LIGHT
                R.id.rbThemeDark  -> PrefsManager.THEME_DARK
                else              -> PrefsManager.THEME_SYSTEM
            }
            if (newTheme != prefs.themeMode) {
                prefs.themeMode = newTheme
                prefs.applyTheme()
            }
        }

        b.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val newLang = if (checkedId == R.id.rbLangEn) PrefsManager.LANG_EN else PrefsManager.LANG_TH
            if (newLang != prefs.language) {
                prefs.language = newLang
                dialog.dismiss()
                recreate()
            }
        }
    }

    // ─── About dialog ────────────────────────────────────────────────────────

    private fun showAboutDialog() {
        val dialogBinding = DialogAboutBinding.inflate(LayoutInflater.from(this))
        dialogBinding.tvAboutVersion.text = getString(
            R.string.about_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.btn_close, null)
            .create()

        // Don't re-check if a download is already in progress
        when (viewModel.selfUpdateState.value) {
            is SelfUpdateState.Downloading, is SelfUpdateState.ReadyToInstall -> Unit
            else -> viewModel.checkSelfUpdate()
        }

        val updateObserver = Observer<SelfUpdateState> { state ->
            if (!dialog.isShowing && state !is SelfUpdateState.Downloading) return@Observer
            updateAboutDialogState(
                b = dialogBinding,
                state = state,
                onCancelClick = {
                    viewModel.cancelSelfUpdate()
                    dialog.dismiss()
                },
                onUpdateClick = { _, downloadUrl ->
                    dialog.dismiss()
                    startSelfUpdate(downloadUrl)
                }
            )
        }
        viewModel.selfUpdateState.observe(this, updateObserver)
        dialog.setOnDismissListener { viewModel.selfUpdateState.removeObserver(updateObserver) }

        dialog.show()
    }

    private fun updateAboutDialogState(
        b: DialogAboutBinding,
        state: SelfUpdateState,
        onCancelClick: () -> Unit,
        onUpdateClick: (tagName: String, downloadUrl: String) -> Unit
    ) {
        when (state) {
            is SelfUpdateState.Checking -> {
                b.tvUpdateStatus.text = getString(R.string.about_checking_update)
                b.progressSelfUpdate.isIndeterminate = true
                b.progressSelfUpdate.visibility = View.VISIBLE
                b.tvDownloadProgress.visibility = View.GONE
                b.btnSelfUpdate.visibility = View.GONE
            }
            is SelfUpdateState.UpToDate -> {
                b.tvUpdateStatus.text = getString(R.string.about_up_to_date)
                b.progressSelfUpdate.visibility = View.GONE
                b.tvDownloadProgress.visibility = View.GONE
                b.btnSelfUpdate.visibility = View.GONE
            }
            is SelfUpdateState.UpdateAvailable -> {
                b.tvUpdateStatus.text = getString(R.string.about_update_available, state.tagName)
                b.progressSelfUpdate.visibility = View.GONE
                b.tvDownloadProgress.visibility = View.GONE
                b.btnSelfUpdate.visibility = View.VISIBLE
                b.btnSelfUpdate.text = getString(R.string.btn_update_now)
                b.btnSelfUpdate.isEnabled = true
                b.btnSelfUpdate.setOnClickListener {
                    onUpdateClick(state.tagName, state.downloadUrl)
                }
            }
            is SelfUpdateState.Downloading -> {
                b.tvUpdateStatus.text = getString(R.string.about_downloading_update)
                b.progressSelfUpdate.isIndeterminate = false
                b.progressSelfUpdate.progress = state.progress
                b.progressSelfUpdate.visibility = View.VISIBLE
                b.tvDownloadProgress.text = "${state.progress}%"
                b.tvDownloadProgress.visibility = View.VISIBLE
                b.btnSelfUpdate.visibility = View.VISIBLE
                b.btnSelfUpdate.text = getString(R.string.about_downloading_progress, state.progress)
                b.btnSelfUpdate.isEnabled = true
                b.btnSelfUpdate.setOnClickListener { onCancelClick() }
            }
            is SelfUpdateState.ReadyToInstall -> {
                b.tvUpdateStatus.text = getString(R.string.about_ready_to_install)
                b.progressSelfUpdate.visibility = View.GONE
                b.tvDownloadProgress.visibility = View.GONE
                val apkFile = File(getExternalFilesDir(null), "bear-store-update.apk")
                if (apkFile.exists()) {
                    b.btnSelfUpdate.visibility = View.VISIBLE
                    b.btnSelfUpdate.text = getString(R.string.btn_install)
                    b.btnSelfUpdate.isEnabled = true
                    b.btnSelfUpdate.setOnClickListener { installApkFromFile(apkFile) }
                } else {
                    b.btnSelfUpdate.visibility = View.GONE
                }
            }
            is SelfUpdateState.Error -> {
                b.tvUpdateStatus.text = getString(R.string.about_update_error, state.msg)
                b.progressSelfUpdate.visibility = View.GONE
                b.tvDownloadProgress.visibility = View.GONE
                b.btnSelfUpdate.visibility = View.GONE
            }
        }
    }

    private fun startSelfUpdate(downloadUrl: String) {
        val destFile = File(getExternalFilesDir(null), "bear-store-update.apk")
        viewModel.startSelfUpdate(downloadUrl, destFile)
        Toast.makeText(this, R.string.toast_self_update_start, Toast.LENGTH_SHORT).show()
    }

    // ─── Download / Install / Uninstall ──────────────────────────────────────

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
        // Remove stale APK files from previous versions of this app
        getExternalFilesDir(null)?.listFiles { file ->
            file.name.startsWith("${app.id}-") && file.name.endsWith(".apk")
        }?.forEach { it.delete() }

        val fileName = "${app.id}-${app.version.ifBlank { "latest" }}.apk"
        val destFile = File(getExternalFilesDir(null), fileName)
        downloadedFiles[app.id] = destFile
        viewModel.startDownload(app, destFile)
    }

    private fun installApk(app: AppItem) {
        val file = downloadedFiles[app.id] ?: return
        installApkFromFile(file)
    }

    private fun installApkFromFile(file: File) {
        if (!file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_install_failed, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, R.string.toast_uninstall_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ─── App detail dialog ───────────────────────────────────────────────────

    private fun showAppDetailDialog(app: AppItem) {
        val b = DialogAppDetailBinding.inflate(LayoutInflater.from(this))
        b.tvDetailName.text = app.name
        b.tvDetailVersion.text = if (app.version.isNotBlank())
            getString(R.string.version_format, app.version)
        else
            getString(R.string.version_unspecified)

        val iconRes = when (app.id) {
            "youtube"       -> R.drawable.ic_app_youtube
            "youtube_music" -> R.drawable.ic_app_youtube_music
            "bear_microg"   -> R.drawable.ic_app_microg
            else            -> R.drawable.ic_bear_logo
        }
        if (app.iconUrl.isNotBlank()) {
            b.ivDetailIcon.load(app.iconUrl) {
                placeholder(iconRes)
                error(iconRes)
                crossfade(true)
            }
        } else {
            b.ivDetailIcon.setImageResource(iconRes)
        }

        if (app.description.isNotBlank()) {
            b.layoutDetailDescription.visibility = View.VISIBLE
            b.tvDetailDescription.text = app.description
        }
        if (app.changelog.isNotBlank()) {
            b.layoutDetailChangelog.visibility = View.VISIBLE
            b.tvDetailChangelog.text = app.changelog
        }
        if (app.description.isBlank() && app.changelog.isBlank()) {
            b.tvDetailEmpty.visibility = View.VISIBLE
        }

        MaterialAlertDialogBuilder(this)
            .setView(b.root)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    // ─── Notification permission ─────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
