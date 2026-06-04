package app.bear.store.model

data class AppsConfig(val apps: List<AppItem>)

data class AppItem(
    val id: String,
    val name: String,
    val version: String,
    val updatedAt: String,
    val downloadUrl: String,
    val packageName: String = ""
) {
    val hasDownloadUrl: Boolean get() = downloadUrl.isNotBlank()
}
