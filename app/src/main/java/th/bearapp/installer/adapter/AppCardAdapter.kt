package th.bearapp.installer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import th.bearapp.installer.R
import th.bearapp.installer.databinding.ItemAppCardBinding
import th.bearapp.installer.model.AppItem

enum class CardDownloadState { IDLE, DOWNLOADING, COMPLETE, ERROR }

data class AppCardState(
    val app: AppItem,
    val state: CardDownloadState = CardDownloadState.IDLE,
    val progress: Int = 0
)

class AppCardAdapter(
    private val onDownload: (AppItem) -> Unit,
    private val onInstall: (AppItem) -> Unit
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
        items[idx] = items[idx].copy(state = state, progress = progress)
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
        with(holder.binding) {
            tvAppName.text = app.name
            tvVersion.text = if (app.version.isNotBlank()) "เวอร์ชัน ${app.version}" else "ยังไม่ระบุเวอร์ชัน"
            tvUpdatedAt.text = if (app.updatedAt.isNotBlank()) "อัปเดต: ${app.updatedAt}" else ""
            tvUpdatedAt.visibility = if (app.updatedAt.isNotBlank()) View.VISIBLE else View.GONE
            ivAppIcon.setImageResource(iconResFor(app.id))

            when (cardState.state) {
                CardDownloadState.IDLE -> {
                    progressLayout.visibility = View.GONE
                    btnDownload.visibility = View.VISIBLE
                    btnInstall.visibility = View.GONE
                    btnDownload.isEnabled = app.hasDownloadUrl
                    btnDownload.text = if (app.hasDownloadUrl) "ดาวน์โหลด" else "ยังไม่ระบุ"
                    btnDownload.setOnClickListener { onDownload(app) }
                }
                CardDownloadState.DOWNLOADING -> {
                    progressLayout.visibility = View.VISIBLE
                    progressBar.progress = cardState.progress
                    tvProgress.text = "${cardState.progress}%"
                    btnDownload.visibility = View.GONE
                    btnInstall.visibility = View.GONE
                }
                CardDownloadState.COMPLETE -> {
                    progressLayout.visibility = View.GONE
                    btnDownload.visibility = View.GONE
                    btnInstall.visibility = View.VISIBLE
                    btnInstall.setOnClickListener { onInstall(app) }
                }
                CardDownloadState.ERROR -> {
                    progressLayout.visibility = View.GONE
                    btnDownload.visibility = View.VISIBLE
                    btnInstall.visibility = View.GONE
                    btnDownload.isEnabled = app.hasDownloadUrl
                    btnDownload.text = "ลองใหม่"
                    btnDownload.setOnClickListener { onDownload(app) }
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
