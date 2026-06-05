package app.bear.store.model

data class AppsConfig(val apps: List<AppItem>)

data class AppItem(
    val id: String,
    val name: String,
    val version: String = "",
    val updatedAt: String = "",
    val downloadUrl: String = "",
    val packageName: String = "",
    val iconUrl: String = "",
    val description: String = "",
    val changelog: String = "",
    val githubOwner: String = "",
    val githubRepo: String = ""
) {
    val hasDownloadUrl: Boolean get() = downloadUrl.isNotBlank()
    val isGitHubManaged: Boolean get() = githubOwner.isNotBlank() && githubRepo.isNotBlank()
}
