package app.bear.store.network

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import app.bear.store.R
import app.bear.store.model.AppItem
import app.bear.store.model.AppRelease
import app.bear.store.model.AppsConfig
import app.bear.store.util.AppLogger
import app.bear.store.util.RetryUtils
import java.util.concurrent.TimeUnit

/**
 * Service for fetching GitHub API and app configuration
 * Enhanced with retry logic, better error handling, and input validation
 */
class GitHubApiService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch apps configuration with retry logic
     */
    suspend fun getAppsConfig(owner: String, repo: String, branch: String = "main"): AppsConfig {
        return RetryUtils.withRetry(
            maxRetries = 3,
            retryableException = { RetryUtils.isNetworkRetryable(it) }
        ) {
            val url = "https://raw.githubusercontent.com/$owner/$repo/$branch/apps_config.json"
            val response = client.newCall(
                Request.Builder().url(url)
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "BearApp-Installer/1.0")
                    .build()
            ).execute()
            
            when (response.code) {
                403, 429 -> throw Exception(context.getString(R.string.error_rate_limit))
                else -> {
                    val body = response.body?.string() ?: throw Exception(context.getString(R.string.error_no_data))
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                    val config = parseAppsConfig(body)
                    // Validate the configuration
                    config.validate()
                    config
                }
            }
        }
    }

    /**
     * Fetch the latest release JSON for a repository
     * Uses cache to avoid duplicate requests for the same repo
     */
    suspend fun fetchRelease(owner: String, repo: String): JsonObject {
        return RetryUtils.withRetry(
            maxRetries = 3,
            retryableException = { RetryUtils.isNetworkRetryable(it) }
        ) {
            val response = client.newCall(
                Request.Builder()
                    .url("https://api.github.com/repos/$owner/$repo/releases/latest")
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "BearApp-Installer/1.0")
                    .build()
            ).execute()
            
            when (response.code) {
                403, 429 -> throw Exception(context.getString(R.string.error_rate_limit))
                else -> {
                    val body = response.body?.string() ?: throw Exception(context.getString(R.string.error_no_data))
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                    JsonParser.parseString(body).asJsonObject
                }
            }
        }
    }

    /**
     * Parse AppRelease from a release JSON object using the first APK asset
     */
    fun parseRelease(obj: JsonObject): AppRelease {
        val assets = obj.getAsJsonArray("assets")
        val apkAsset = assets?.firstOrNull { el ->
            el.asJsonObject.get("name")?.asString?.endsWith(".apk") == true
        }?.asJsonObject
        return AppRelease(
            tagName = obj.get("tag_name")?.asString ?: "",
            name = obj.get("name")?.asString ?: "",
            body = obj.get("body")?.asString ?: "",
            publishedAt = obj.get("published_at")?.asString ?: "",
            apkUrl = apkAsset?.get("browser_download_url")?.asString,
            apkSize = apkAsset?.get("size")?.asLong ?: 0L
        )
    }

    /**
     * Parse AppRelease by matching asset filename prefix
     * e.g. filePrefix="YouTube" matches "YouTube_21_21_80.apk" → version "21.21.80"
     */
    fun parseAssetFromRelease(obj: JsonObject, filePrefix: String): AppRelease? {
        val assets = obj.getAsJsonArray("assets") ?: return null
        val prefix = "${filePrefix}_"
        val asset = assets.firstOrNull { el ->
            val name = el.asJsonObject.get("name")?.asString ?: ""
            name.startsWith(prefix) && name.endsWith(".apk")
        }?.asJsonObject ?: return null
        val assetName = asset.get("name")?.asString ?: return null
        val downloadUrl = asset.get("browser_download_url")?.asString ?: return null
        val size = asset.get("size")?.asLong ?: 0L
        val version = assetName.removePrefix(prefix).removeSuffix(".apk").replace("_", ".")
        return AppRelease(
            tagName = version,
            name = obj.get("name")?.asString ?: "",
            body = obj.get("body")?.asString ?: "",
            publishedAt = obj.get("published_at")?.asString ?: "",
            apkUrl = downloadUrl,
            apkSize = size
        )
    }

    /**
     * Get latest release - convenience wrapper for fetch + parse
     */
    suspend fun getLatestRelease(owner: String, repo: String): AppRelease = parseRelease(fetchRelease(owner, repo))

    private fun parseAppsConfig(json: String): AppsConfig {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            val appsArray = obj.getAsJsonArray("apps") ?: return AppsConfig(emptyList())
            val apps = appsArray.mapNotNull { el ->
                try {
                    val a = el.asJsonObject
                    AppItem(
                        id = a.get("id")?.asString?.trim() ?: return@mapNotNull null,
                        name = a.get("name")?.asString?.trim() ?: return@mapNotNull null,
                        version = a.get("version")?.asString?.trim() ?: "",
                        updatedAt = a.get("updated_at")?.asString?.trim() ?: "",
                        downloadUrl = a.get("download_url")?.asString?.trim() ?: "",
                        packageName = a.get("package_name")?.asString?.trim() ?: return@mapNotNull null,
                        iconUrl = a.get("icon_url")?.asString?.trim() ?: "",
                        description = a.get("description")?.asString?.trim() ?: "",
                        changelog = a.get("changelog")?.asString?.trim() ?: "",
                        githubOwner = a.get("github_owner")?.asString?.trim() ?: "",
                        githubRepo = a.get("github_repo")?.asString?.trim() ?: "",
                        githubFilePrefix = a.get("github_file_prefix")?.asString?.trim() ?: ""
                    )
                } catch (e: Exception) {
                    AppLogger.w("Failed to parse app item: ${e.message}")
                    null
                }
            }
            AppsConfig(apps)
        } catch (e: Exception) {
            AppLogger.e("Failed to parse apps config: ${e.message}", e)
            AppsConfig(emptyList())
        }
    }
}
