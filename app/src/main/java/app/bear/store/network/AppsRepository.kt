package app.bear.store.network

import app.bear.store.model.AppItem
import app.bear.store.model.AppRelease
import com.google.gson.JsonObject

/**
 * Resolves GitHub-managed [AppItem]s against their latest release.
 *
 * This logic used to be duplicated between [app.bear.store.viewmodel.MainViewModel]
 * and [app.bear.store.worker.UpdateCheckWorker] — both fetched releases, cached
 * the JSON per "owner/repo", and copied the result fields onto AppItem the same
 * way. It now lives in one place so a fix or behavior change only needs to
 * happen once.
 */
class AppsRepository(private val apiService: GitHubApiService) {

    /** An [AppItem] paired with the [AppRelease] it was resolved from, if any. */
    data class ResolvedApp(val app: AppItem, val release: AppRelease?)

    /**
     * Resolves [apps] against their latest GitHub release. Non-GitHub-managed
     * apps, and any app whose lookup fails, are returned unchanged (with a
     * null release). Release JSON is cached per "owner/repo" within this call
     * so apps sharing a repo (e.g. YouTube + YouTube Music) only trigger one
     * network request.
     */
    fun resolve(apps: List<AppItem>): List<ResolvedApp> {
        val jsonCache = mutableMapOf<String, JsonObject>()
        return apps.map { app ->
            if (!app.isGitHubManaged) return@map ResolvedApp(app, null)
            try {
                val key = "${app.githubOwner}/${app.githubRepo}"
                val releaseJson = jsonCache.getOrPut(key) {
                    apiService.fetchRelease(app.githubOwner, app.githubRepo)
                }
                val release = if (app.githubFilePrefix.isNotBlank()) {
                    apiService.parseAssetFromRelease(releaseJson, app.githubFilePrefix)
                        ?: return@map ResolvedApp(app, null)
                } else {
                    apiService.parseRelease(releaseJson)
                }
                val updated = app.copy(
                    version = release.tagName.trimStart('v', 'V'),
                    downloadUrl = release.apkUrl ?: app.downloadUrl,
                    changelog = release.body.ifBlank { app.changelog },
                    downloadSize = release.apkSize,
                    sha256Digest = release.apkDigest
                )
                ResolvedApp(updated, release)
            } catch (_: Exception) {
                ResolvedApp(app, null)
            }
        }
    }
}
