package app.bear.store

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import app.bear.store.adapter.CardDownloadState
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
    private val pendingApkCleanup = mutableSetOf<String>()
    private var pendingDownloadApp: AppItem? = null
    private var pendingAutoDownload = false
    private var currentChipFilter = ChipFilter.ALL

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (viewModel.errorMessage.value != null && viewModel.isLoading.value != true) {
                    viewModel.fetchApps()
                }
            }
        }
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
            if (pendingAutoDownload) {
                pendingAutoDownload = false
                startAutoDownloads()
            } else {
                pendingDownloadApp?.let { beginDownload(it) }
            }
        }
        pendingDownloadApp = null
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

        currentChipFilter = ChipFilter.values().getOrElse(
            savedInstanceState?.getInt(KEY_CHIP_FILTER, 0) ?: 0
        ) { ChipFilter.ALL }
        pendingAutoDownload = savedInstanceState?.getBoolean(KEY_PENDING_AUTO_DOWNLOAD, false) ?: false

        restoreDownloadedFiles()
        cleanupOrphanedApks()
        setupToolbar()
        setupRecyclerView()
        observeViewModel()
        setupListeners()
        savedInstanceState?.getString(KEY_SEARCH_QUERY)?.let { q ->
            if (q.isNotBlank()) binding.etSearch.setText(q)
        }
        requestNotificationPermission()
        val savedLang = savedInstanceState?.getString(KEY_LANGUAGE)
        val langChanged = savedLang != null && savedLang != PrefsManager(this).language
        if (viewModel.apps.value.isNullOrEmpty() || langChanged) {
            viewModel.fetchApps()
        } else {
            binding.shimmerLayout.visibility = View.GONE
            processIntent(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
    }

    override fun onStop() {
        super.onStop()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(networkCallback)
    }

    override fun onResume() {
        super.onResume()
        viewModel.resetCompletedDownloads(downloadedFiles.keys.toSet())
        viewModel.refreshInstallStates()
        cleanupSelfUpdateApk()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CHIP_FILTER, currentChipFilter.ordinal)
        outState.putString(KEY_LANGUAGE, PrefsManager(this).language)
        outState.putBoolean(KEY_PENDING_AUTO_DOWNLOAD, pendingAutoDownload)
        outState.putString(KEY_SEARCH_QUERY, binding.etSearch.text?.toString() ?: "")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        processIntent(intent)
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
            onDetails   = { app -> showAppDetailDialog(app) },
            onOpen      = { app -> openApp(app) }
        )
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
        adapter.setChipFilter(currentChipFilter)

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
            binding.tvOfflineBanner.visibility = if (isCache) View.VISIBLE else View.GONE
        }

        viewModel.downloadStates.observe(this) { states ->
            states.forEach { (appId, info) ->
                adapter.updateState(appId, info.state, info.progress, info.error, info.downloadedBytes, info.totalBytes)
            }
            updateUpdateAllButton()
        }

        viewModel.installStates.observe(this) { states ->
            states.forEach { (appId, pair) ->
                adapter.updateInstallState(appId, pair.first, pair.second)
                // Clean up APK after successful install
                if (appId in pendingApkCleanup && pair.first != InstallState.NOT_INSTALLED) {
                    downloadedFiles.remove(appId)?.delete()
                    pendingApkCleanup.remove(appId)
                    viewModel.clearDownloadState(appId)
                }
            }
            updateUpdateAllButton()
        }

        lifecycleScope.launch {
            viewModel.autoInstallTrigger.collect { app ->
                if (PrefsManager(this@MainActivity).autoUpdate) {
                    installApkViaPM(app)
                } else {
                    installApk(app)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.selfUpdateInstallTrigger.collect { file -> installApkFromFile(file) }
        }

        lifecycleScope.launch {
            viewModel.triggerAutoUpdate.collect { autoDownloadUpdates() }
        }

        lifecycleScope.launch {
            (application as BearApplication).pmInstallEvents.collect { (appId, success) ->
                if (appId == null) return@collect
                if (success) {
                    downloadedFiles.remove(appId)?.delete()
                    pendingApkCleanup.remove(appId)
                    viewModel.clearDownloadState(appId)
                    viewModel.refreshInstallStates()
                }
                // on failure, COMPLETE state persists → Install button stays for manual install
            }
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

        // Sync chip group UI with the filter restored from savedInstanceState
        when (currentChipFilter) {
            ChipFilter.UPDATES -> binding.chipUpdates.isChecked = true
            ChipFilter.INSTALLED -> binding.chipInstalled.isChecked = true
            ChipFilter.ALL -> binding.chipAll.isChecked = true
        }

        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when {
                R.id.chipUpdates in checkedIds -> ChipFilter.UPDATES
                R.id.chipInstalled in checkedIds -> ChipFilter.INSTALLED
                else -> ChipFilter.ALL
            }
            currentChipFilter = filter
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

        b.switchAutoUpdate.isChecked = prefs.autoUpdate

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

        b.switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoUpdate = isChecked
        }

        var suppressLangListener = false
        b.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            if (suppressLangListener) return@setOnCheckedChangeListener
            val newLang = if (checkedId == R.id.rbLangEn) PrefsManager.LANG_EN else PrefsManager.LANG_TH
            if (newLang != prefs.language) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_lang_change_title)
                    .setMessage(R.string.dialog_lang_change_message)
                    .setPositiveButton(R.string.btn_confirm) { _, _ ->
                        prefs.language = newLang
                        dialog.dismiss()
                        recreate()
                    }
                    .setNegativeButton(R.string.btn_cancel) { _, _ ->
                        suppressLangListener = true
                        if (prefs.language == PrefsManager.LANG_EN) b.rbLangEn.isChecked = true
                        else b.rbLangTh.isChecked = true
                        suppressLangListener = false
                    }
                    .show()
            }
        }
    }

    // ─── About dialog ────────────────────────────────────────────────────────

    private fun showAboutDialog() {
        val dialogBinding = DialogAboutBinding.inflate(LayoutInflater.from(this))
        dialogBinding.tvAboutVersion.text = getString(
            R.string.about_version_format, BuildConfig.VERSION_NAME
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.btn_close, null)
            .create()

        when (viewModel.selfUpdateState.value) {
            is SelfUpdateState.Downloading, is SelfUpdateState.ReadyToInstall -> Unit
            else -> viewModel.checkSelfUpdate()
        }

        fun render(state: SelfUpdateState) = updateAboutDialogState(
            b = dialogBinding,
            state = state,
            onCancelClick = { viewModel.cancelSelfUpdate(); dialog.dismiss() },
            onUpdateClick = { _, downloadUrl -> startSelfUpdate(downloadUrl) }
        )

        val updateObserver = Observer<SelfUpdateState> { state ->
            if (dialog.isShowing) render(state)
        }
        viewModel.selfUpdateState.observe(this, updateObserver)
        dialog.setOnDismissListener { viewModel.selfUpdateState.removeObserver(updateObserver) }

        dialog.show()
        // Observer fires before show(), so manually initialize the dialog with current state
        viewModel.selfUpdateState.value?.let { render(it) }
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
                b.tvUpdateChangelog.visibility = View.GONE
                b.btnSelfUpdate.visibility = View.GONE
            }
            is SelfUpdateState.UpToDate -> {
                b.tvUpdateStatus.text = getString(R.string.about_up_to_date)
                b.progressSelfUpdate.visibility = View.GONE
                b.tvDownloadProgress.visibility = View.GONE
                b.tvUpdateChangelog.visibility = View.GONE
                b.btnSelfUpdate.visibility = View.GONE
            }
            is SelfUpdateState.UpdateAvailable -> {
                b.tvUpdateStatus.text = getString(R.string.about_update_available, state.tagName)
                b.progressSelfUpdate.visibility = View.GONE
                b.tvDownloadProgress.visibility = View.GONE
                if (state.changelog.isNotBlank()) {
                    b.tvUpdateChangelog.visibility = View.VISIBLE
                    b.tvUpdateChangelog.text = state.changelog.trim()
                } else {
                    b.tvUpdateChangelog.visibility = View.GONE
                }
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
                b.tvUpdateChangelog.visibility = View.GONE
                b.btnSelfUpdate.visibility = View.VISIBLE
                b.btnSelfUpdate.text = getString(R.string.about_downloading_progress, state.progress)
                b.btnSelfUpdate.isEnabled = true
                b.btnSelfUpdate.setOnClickListener { onCancelClick() }
            }
            is SelfUpdateState.ReadyToInstall -> {
                b.tvUpdateStatus.text = getString(R.string.about_ready_to_install)
                b.progressSelfUpdate.visibility = View.GONE
                b.tvDownloadProgress.visibility = View.GONE
                b.tvUpdateChangelog.visibility = View.GONE
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
                b.tvUpdateChangelog.visibility = View.GONE
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

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun checkPermissionAndDownload(app: AppItem) {
        if (!isNetworkAvailable()) {
            Snackbar.make(binding.root, R.string.error_no_network, Snackbar.LENGTH_LONG).show()
            return
        }
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
        pendingApkCleanup.add(app.id)
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_uninstall_title)
            .setMessage(getString(R.string.dialog_uninstall_message, app.name))
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
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
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun openApp(app: AppItem) {
        if (app.packageName.isBlank()) return
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.toast_open_failed, Toast.LENGTH_SHORT).show()
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

    private fun updateUpdateAllButton() {
        val installStates = viewModel.installStates.value ?: return
        val downloadStates = viewModel.downloadStates.value ?: return
        val updateCount = installStates.values.count { it.first == InstallState.UPDATE_AVAILABLE }
        if (updateCount >= 1) {
            binding.btnUpdateAll.text = getString(R.string.btn_update_all, updateCount)
            binding.btnUpdateAll.visibility = View.VISIBLE
            val anyDownloading = installStates.entries.any { (appId, pair) ->
                pair.first == InstallState.UPDATE_AVAILABLE &&
                        downloadStates[appId]?.state == CardDownloadState.DOWNLOADING
            }
            binding.btnUpdateAll.isEnabled = !anyDownloading
        } else {
            binding.btnUpdateAll.visibility = View.GONE
        }
    }

    private fun processIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateCheckWorker.EXTRA_TRIGGER_AUTO_DOWNLOAD, false) != true) return
        if (!viewModel.apps.value.isNullOrEmpty()) viewModel.fetchApps()
    }

    private fun autoDownloadUpdates() {
        if (!PrefsManager(this).autoUpdate) return
        if (!isNetworkAvailable()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingAutoDownload = true
            installPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            return
        }
        startAutoDownloads()
    }

    private fun startAutoDownloads() {
        val apps = viewModel.apps.value ?: return
        val installStates = viewModel.installStates.value ?: return
        val downloadStates = viewModel.downloadStates.value ?: return
        apps.filter { app ->
            app.hasDownloadUrl &&
            installStates[app.id]?.first == InstallState.UPDATE_AVAILABLE &&
            downloadStates[app.id]?.state != CardDownloadState.DOWNLOADING
        }.forEach { app -> beginDownload(app) }
    }

    private fun installApkViaPM(app: AppItem) {
        val file = downloadedFiles[app.id] ?: return
        pendingApkCleanup.add(app.id)
        viewModel.installApkViaPM(app.id, file)
    }

    private fun restoreDownloadedFiles() {
        val states = viewModel.downloadStates.value ?: return
        val dir = getExternalFilesDir(null) ?: return
        states.forEach { (appId, info) ->
            if (info.state == CardDownloadState.DOWNLOADING || info.state == CardDownloadState.COMPLETE) {
                val app = viewModel.getApp(appId) ?: return@forEach
                val file = File(dir, "${appId}-${app.version.ifBlank { "latest" }}.apk")
                if (file.exists()) downloadedFiles[appId] = file
            }
        }
    }

    private fun cleanupOrphanedApks() {
        val trackedNames = downloadedFiles.values.map { it.name }.toSet()
        getExternalFilesDir(null)?.listFiles { file ->
            file.name.endsWith(".apk") &&
            file.name != "bear-store-update.apk" &&
            file.name !in trackedNames
        }?.forEach { it.delete() }
    }

    private fun cleanupSelfUpdateApk() {
        val state = viewModel.selfUpdateState.value
        if (state !is SelfUpdateState.Downloading && state !is SelfUpdateState.ReadyToInstall) {
            getExternalFilesDir(null)?.let { File(it, "bear-store-update.apk").delete() }
        }
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

    companion object {
        private const val KEY_CHIP_FILTER = "chip_filter"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PENDING_AUTO_DOWNLOAD = "pending_auto_download"
        private const val KEY_SEARCH_QUERY = "search_query"
    }
}
