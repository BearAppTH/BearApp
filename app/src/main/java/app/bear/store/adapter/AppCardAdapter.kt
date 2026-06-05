package app.bear.store.adapter

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.bear.store.R
import app.bear.store.databinding.ItemAppCardBinding
import app.bear.store.model.AppItem
import app.bear.store.model.InstallState
import coil.load

enum class CardDownloadState { IDLE, DOWNLOADING, COMPLETE, ERROR }
data class DownloadInfo(
    val state: CardDownloadState,
    val progress: Int = 0,
    val error: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L
)
enum class ChipFilter { ALL, UPDATES, INSTALLED }

data class AppCardState(
    val app: AppItem,
    val downloadState: CardDownloadState = CardDownloadState.IDLE,
    val progress: Int = 0,
    val installState: InstallState = InstallState.NOT_INSTALLED,
    val installedVersion: String? = null,
    val downloadError: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L
)

class AppCardAdapter(
    private val onDownload: (AppItem) -> Unit,
    private val onInstall: (AppItem) -> Unit,
    private val onUninstall: (AppItem) -> Unit,
    private val onCancel: (AppItem) -> Unit,
    private val onDetails: (AppItem) -> Unit,
    private val onOpen: (AppItem) -> Unit
) : RecyclerView.Adapter<AppCardAdapter.ViewHolder>() {

    private val allItems = mutableListOf<AppCardState>()
    private val items = mutableListOf<AppCardState>()
    private var currentQuery = ""
    private var currentChip = ChipFilter.ALL
    var onFilterEmpty: ((Boolean) -> Unit)? = null

    fun setApps(apps: List<AppItem>) {
        val newAll = apps.map { app ->
            allItems.find { it.app.id == app.id }?.copy(app = app) ?: AppCardState(app)
        }
        allItems.clear()
        allItems.addAll(newAll)
        applyFilter()
    }

    fun updateState(appId: String, state: CardDownloadState, progress: Int = 0, error: String? = null, downloadedBytes: Long = 0L, totalBytes: Long = 0L) {
        val allIdx = allItems.indexOfFirst { it.app.id == appId }
        if (allIdx >= 0) allItems[allIdx] = allItems[allIdx].copy(downloadState = state, progress = progress, downloadError = error, downloadedBytes = downloadedBytes, totalBytes = totalBytes)
        val idx = items.indexOfFirst { it.app.id == appId }
        if (idx < 0) return
        items[idx] = items[idx].copy(downloadState = state, progress = progress, downloadError = error, downloadedBytes = downloadedBytes, totalBytes = totalBytes)
        notifyItemChanged(idx)
    }

    fun updateInstallState(appId: String, installState: InstallState, installedVersion: String?) {
        val allIdx = allItems.indexOfFirst { it.app.id == appId }
        if (allIdx >= 0) allItems[allIdx] = allItems[allIdx].copy(installState = installState, installedVersion = installedVersion)
        if (currentChip != ChipFilter.ALL) {
            applyFilter()
            return
        }
        val idx = items.indexOfFirst { it.app.id == appId }
        if (idx < 0) return
        items[idx] = items[idx].copy(installState = installState, installedVersion = installedVersion)
        notifyItemChanged(idx)
    }

    fun filter(query: String) {
        currentQuery = query
        applyFilter()
    }

    fun setChipFilter(filter: ChipFilter) {
        currentChip = filter
        applyFilter()
    }

    private fun applyFilter() {
        var filtered = allItems.toList()
        if (currentQuery.isNotBlank()) {
            filtered = filtered.filter { it.app.name.contains(currentQuery, ignoreCase = true) }
        }
        filtered = when (currentChip) {
            ChipFilter.ALL -> filtered
            ChipFilter.UPDATES -> filtered.filter { it.installState == InstallState.UPDATE_AVAILABLE }
            ChipFilter.INSTALLED -> filtered.filter { it.installState != InstallState.NOT_INSTALLED }
        }
        val diffResult = DiffUtil.calculateDiff(DiffCallback(items.toList(), filtered))
        items.clear()
        items.addAll(filtered)
        diffResult.dispatchUpdatesTo(this)
        val isFiltering = currentQuery.isNotBlank() || currentChip != ChipFilter.ALL
        onFilterEmpty?.invoke(filtered.isEmpty() && isFiltering)
    }

    inner class ViewHolder(val binding: ItemAppCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val cardState = items[position]
        val app = cardState.app
        val ctx = holder.itemView.context

        with(holder.binding) {
            root.setOnClickListener { onDetails(app) }

            if (currentQuery.isNotBlank()) {
                val idx = app.name.indexOf(currentQuery, ignoreCase = true)
                if (idx >= 0) {
                    val spannable = SpannableString(app.name)
                    val primaryColor = ContextCompat.getColor(ctx, R.color.primary)
                    spannable.setSpan(StyleSpan(Typeface.BOLD), idx, idx + currentQuery.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(ForegroundColorSpan(primaryColor), idx, idx + currentQuery.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    tvAppName.text = spannable
                } else {
                    tvAppName.text = app.name
                }
            } else {
                tvAppName.text = app.name
            }

            tvUpdatedAt.text = if (app.updatedAt.isNotBlank())
                ctx.getString(R.string.updated_at_format, app.updatedAt) else ""
            tvUpdatedAt.visibility = if (app.updatedAt.isNotBlank()) View.VISIBLE else View.GONE

            if (app.iconUrl.isNotBlank()) {
                ivAppIcon.load(app.iconUrl) {
                    placeholder(iconResFor(app.id))
                    error(iconResFor(app.id))
                    crossfade(true)
                }
            } else {
                ivAppIcon.setImageResource(iconResFor(app.id))
            }

            // Version display
            when (cardState.installState) {
                InstallState.NOT_INSTALLED -> {
                    tvVersionNew.visibility = View.GONE
                    tvVersionInstalled.text = if (app.version.isNotBlank())
                        ctx.getString(R.string.version_format, app.version)
                    else
                        ctx.getString(R.string.version_unspecified)
                    tvVersionInstalled.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                }
                InstallState.INSTALLED_UP_TO_DATE -> {
                    tvVersionNew.visibility = View.GONE
                    tvVersionInstalled.text = ctx.getString(
                        R.string.version_installed_check,
                        cardState.installedVersion ?: app.version
                    )
                    tvVersionInstalled.setTextColor(ContextCompat.getColor(ctx, R.color.success))
                }
                InstallState.UPDATE_AVAILABLE -> {
                    tvVersionNew.visibility = View.VISIBLE
                    tvVersionNew.text = ctx.getString(R.string.version_new, app.version)
                    tvVersionInstalled.text = ctx.getString(
                        R.string.version_installed,
                        cardState.installedVersion ?: ""
                    )
                    tvVersionInstalled.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                }
            }

            // Action buttons
            when (cardState.downloadState) {
                CardDownloadState.IDLE -> {
                    tvDownloadError.visibility = View.GONE
                    progressLayout.visibility = View.GONE
                    btnInstall.visibility = View.GONE
                    layoutActionRow.visibility = View.VISIBLE

                    val isInstalled = cardState.installState != InstallState.NOT_INSTALLED
                    val hasUpdate = cardState.installState == InstallState.UPDATE_AVAILABLE

                    when {
                        isInstalled && !hasUpdate -> {
                            // Open + Uninstall buttons for installed/up-to-date apps
                            btnDownload.visibility = View.VISIBLE
                            btnDownload.isEnabled = true
                            btnDownload.text = ctx.getString(R.string.btn_open)
                            btnDownload.backgroundTintList =
                                ContextCompat.getColorStateList(ctx, R.color.success)
                            btnDownload.setOnClickListener { onOpen(app) }
                            btnUninstall.visibility = View.VISIBLE
                            btnUninstall.isEnabled = true
                            (btnUninstall.layoutParams as LinearLayout.LayoutParams).marginStart =
                                root.context.resources.getDimensionPixelSize(R.dimen.btn_gap)
                            btnUninstall.setOnClickListener { onUninstall(app) }
                        }
                        hasUpdate -> {
                            btnDownload.visibility = View.VISIBLE
                            btnDownload.isEnabled = app.hasDownloadUrl
                            btnDownload.backgroundTintList =
                                ContextCompat.getColorStateList(ctx, R.color.primary)
                            val sizeStr = if (app.downloadSize > 0L) " (${formatFileSize(app.downloadSize)})" else ""
                            btnDownload.text = if (app.hasDownloadUrl)
                                ctx.getString(R.string.btn_update) + sizeStr
                            else
                                ctx.getString(R.string.url_unavailable)
                            btnDownload.setOnClickListener { onDownload(app) }
                            btnUninstall.visibility = View.VISIBLE
                            btnUninstall.isEnabled = true
                            (btnUninstall.layoutParams as LinearLayout.LayoutParams).marginStart =
                                root.context.resources.getDimensionPixelSize(R.dimen.btn_gap)
                            btnUninstall.setOnClickListener { onUninstall(app) }
                        }
                        else -> {
                            btnDownload.visibility = View.VISIBLE
                            btnDownload.isEnabled = app.hasDownloadUrl
                            btnDownload.backgroundTintList =
                                ContextCompat.getColorStateList(ctx, R.color.primary)
                            val sizeStr = if (app.downloadSize > 0L) " (${formatFileSize(app.downloadSize)})" else ""
                            btnDownload.text = if (app.hasDownloadUrl)
                                ctx.getString(R.string.btn_download) + sizeStr
                            else
                                ctx.getString(R.string.url_unavailable)
                            btnDownload.setOnClickListener { onDownload(app) }
                            btnUninstall.visibility = View.GONE
                            btnUninstall.setOnClickListener(null)
                        }
                    }
                }
                CardDownloadState.DOWNLOADING -> {
                    tvDownloadError.visibility = View.GONE
                    progressLayout.visibility = View.VISIBLE
                    progressBar.progress = cardState.progress
                    tvProgress.text = if (cardState.totalBytes > 0L) {
                        "${cardState.progress}% (${formatFileSize(cardState.downloadedBytes)} / ${formatFileSize(cardState.totalBytes)})"
                    } else {
                        "${cardState.progress}%"
                    }
                    btnCancel.setOnClickListener { onCancel(app) }
                    btnInstall.visibility = View.GONE
                    layoutActionRow.visibility = View.GONE
                }
                CardDownloadState.COMPLETE -> {
                    tvDownloadError.visibility = View.GONE
                    progressLayout.visibility = View.GONE
                    btnInstall.visibility = View.VISIBLE
                    val isUpdate = cardState.installState != InstallState.NOT_INSTALLED
                    btnInstall.text = if (isUpdate)
                        ctx.getString(R.string.btn_install_update)
                    else
                        ctx.getString(R.string.btn_install)
                    btnInstall.setOnClickListener { onInstall(app) }
                    layoutActionRow.visibility = View.GONE
                }
                CardDownloadState.ERROR -> {
                    progressLayout.visibility = View.GONE
                    btnInstall.visibility = View.GONE
                    tvDownloadError.visibility = if (cardState.downloadError != null) View.VISIBLE else View.GONE
                    tvDownloadError.text = cardState.downloadError ?: ""
                    layoutActionRow.visibility = View.VISIBLE

                    btnDownload.visibility = View.VISIBLE
                    btnDownload.isEnabled = app.hasDownloadUrl
                    btnDownload.backgroundTintList =
                        ContextCompat.getColorStateList(ctx, R.color.primary)
                    btnDownload.text = ctx.getString(R.string.btn_retry)
                    btnDownload.setOnClickListener { onDownload(app) }

                    val isInstalled = cardState.installState != InstallState.NOT_INSTALLED
                    if (isInstalled) {
                        btnUninstall.visibility = View.VISIBLE
                        btnUninstall.isEnabled = true
                        (btnUninstall.layoutParams as LinearLayout.LayoutParams).marginStart =
                            root.context.resources.getDimensionPixelSize(R.dimen.btn_gap)
                        btnUninstall.setOnClickListener { onUninstall(app) }
                    } else {
                        btnUninstall.visibility = View.GONE
                        btnUninstall.setOnClickListener(null)
                    }
                }
            }
        }
    }

    override fun getItemCount() = items.size

    private fun formatFileSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / (1024f * 1024f))} MB"
    }

    private fun iconResFor(id: String) = when (id) {
        "youtube" -> R.drawable.ic_app_youtube
        "youtube_music" -> R.drawable.ic_app_youtube_music
        "bear_microg" -> R.drawable.ic_app_microg
        else -> R.drawable.ic_bear_logo
    }

    private class DiffCallback(
        private val oldList: List<AppCardState>,
        private val newList: List<AppCardState>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) =
            oldList[oldPos].app.id == newList[newPos].app.id
        override fun areContentsTheSame(oldPos: Int, newPos: Int) =
            oldList[oldPos] == newList[newPos]
    }
}
