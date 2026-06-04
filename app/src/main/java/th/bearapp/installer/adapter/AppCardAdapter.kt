package th.bearapp.installer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import th.bearapp.installer.R
import th.bearapp.installer.databinding.ItemAppCardBinding
import th.bearapp.installer.model.AppItem
import th.bearapp.installer.model.InstallState

enum class CardDownloadState { IDLE, DOWNLOADING, COMPLETE, ERROR }

data class AppCardState(
    val app: AppItem,
    val downloadState: CardDownloadState = CardDownloadState.IDLE,
    val progress: Int = 0,
    val installState: InstallState = InstallState.NOT_INSTALLED,
    val installedVersion: String? = null
)

class AppCardAdapter(
    private val onDownload: (AppItem) -> Unit,
    private val onInstall: (AppItem) -> Unit,
    private val onUninstall: (AppItem) -> Unit
) : RecyclerView.Adapter<AppCardAdapter.ViewHolder>() {

    private val items = mutableListOf<AppCardState>()

    fun setApps(apps: List<AppItem>) {
        items.clear()
        items.addAll(apps.map { AppCardState(it) })
        notifyDataSetChanged()
    }

    fun updateState(appId: String, state: CardDownloadState, progress: Int = 0) {
        val idx = items.indexOfFirst { it.app.id == appId }
        if (idx < 0) return
        items[idx] = items[idx].copy(downloadState = state, progress = progress)
        notifyItemChanged(idx)
    }

    fun updateInstallState(appId: String, installState: InstallState, installedVersion: String?) {
        val idx = items.indexOfFirst { it.app.id == appId }
        if (idx < 0) return
        items[idx] = items[idx].copy(installState = installState, installedVersion = installedVersion)
        notifyItemChanged(idx)
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
            tvAppName.text = app.name
            tvUpdatedAt.text = if (app.updatedAt.isNotBlank()) "อัปเดต: ${app.updatedAt}" else ""
            tvUpdatedAt.visibility = if (app.updatedAt.isNotBlank()) View.VISIBLE else View.GONE
            ivAppIcon.setImageResource(iconResFor(app.id))

            // Version display
            when (cardState.installState) {
                InstallState.NOT_INSTALLED -> {
                    tvVersionNew.visibility = View.GONE
                    tvVersionInstalled.text = if (app.version.isNotBlank()) "เวอร์ชัน ${app.version}" else "ยังไม่ระบุเวอร์ชัน"
                    tvVersionInstalled.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                }
                InstallState.INSTALLED_UP_TO_DATE -> {
                    tvVersionNew.visibility = View.GONE
                    tvVersionInstalled.text = "✓ ติดตั้งอยู่: ${cardState.installedVersion ?: app.version}"
                    tvVersionInstalled.setTextColor(ContextCompat.getColor(ctx, R.color.success))
                }
                InstallState.UPDATE_AVAILABLE -> {
                    tvVersionNew.visibility = View.VISIBLE
                    tvVersionNew.text = "ใหม่: ${app.version}"
                    tvVersionInstalled.text = "ติดตั้งอยู่: ${cardState.installedVersion ?: ""}"
                    tvVersionInstalled.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                }
            }

            // Action buttons
            when (cardState.downloadState) {
                CardDownloadState.IDLE -> {
                    progressLayout.visibility = View.GONE
                    btnInstall.visibility = View.GONE
                    layoutActionRow.visibility = View.VISIBLE

                    val isInstalled = cardState.installState != InstallState.NOT_INSTALLED
                    val hasUpdate = cardState.installState == InstallState.UPDATE_AVAILABLE

                    when {
                        isInstalled && !hasUpdate -> {
                            btnDownload.visibility = View.GONE
                            btnUninstall.visibility = View.VISIBLE
                            btnUninstall.setOnClickListener { onUninstall(app) }
                        }
                        hasUpdate -> {
                            btnDownload.visibility = View.VISIBLE
                            btnDownload.isEnabled = app.hasDownloadUrl
                            btnDownload.text = if (app.hasDownloadUrl) "อัปเดต" else "ยังไม่ระบุ"
                            btnDownload.setOnClickListener { onDownload(app) }
                            btnUninstall.visibility = View.VISIBLE
                            btnUninstall.setOnClickListener { onUninstall(app) }
                        }
                        else -> {
                            btnDownload.visibility = View.VISIBLE
                            btnDownload.isEnabled = app.hasDownloadUrl
                            btnDownload.text = if (app.hasDownloadUrl) "ดาวน์โหลด" else "ยังไม่ระบุ"
                            btnDownload.setOnClickListener { onDownload(app) }
                            btnUninstall.visibility = View.GONE
                        }
                    }
                }
                CardDownloadState.DOWNLOADING -> {
                    progressLayout.visibility = View.VISIBLE
                    progressBar.progress = cardState.progress
                    tvProgress.text = "${cardState.progress}%"
                    btnInstall.visibility = View.GONE
                    layoutActionRow.visibility = View.GONE
                }
                CardDownloadState.COMPLETE -> {
                    progressLayout.visibility = View.GONE
                    btnInstall.visibility = View.VISIBLE
                    val isUpdate = cardState.installState != InstallState.NOT_INSTALLED
                    btnInstall.text = if (isUpdate) "ติดตั้ง / อัปเดต" else "ติดตั้ง"
                    btnInstall.setOnClickListener { onInstall(app) }
                    layoutActionRow.visibility = View.GONE
                }
                CardDownloadState.ERROR -> {
                    progressLayout.visibility = View.GONE
                    btnInstall.visibility = View.GONE
                    layoutActionRow.visibility = View.VISIBLE

                    btnDownload.visibility = View.VISIBLE
                    btnDownload.isEnabled = app.hasDownloadUrl
                    btnDownload.text = "ลองใหม่"
                    btnDownload.setOnClickListener { onDownload(app) }

                    val isInstalled = cardState.installState != InstallState.NOT_INSTALLED
                    btnUninstall.visibility = if (isInstalled) View.VISIBLE else View.GONE
                    if (isInstalled) btnUninstall.setOnClickListener { onUninstall(app) }
                }
            }
        }
    }

    override fun getItemCount() = items.size

    private fun iconResFor(id: String) = when (id) {
        "youtube" -> R.drawable.ic_app_youtube
        "youtube_music" -> R.drawable.ic_app_youtube_music
        "bear_microg" -> R.drawable.ic_app_microg
        else -> R.drawable.ic_bear_logo
    }
}
