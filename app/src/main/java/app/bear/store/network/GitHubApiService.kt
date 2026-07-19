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
import java.util.concurrent.TimeUnit

class GitHubApiService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val proxyBase = "https://bearapp-github-proxy.boss-borwornwong.workers.dev"

    fun getAppsConfig(owner: String, repo: String, branch: String = "main"): AppsConfig {
        val url = "$proxyBase/raw/$owner/$repo/$branch/apps_config.json"
        val response = client.newCall(
            Request.Builder().url(url)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "BearApp-Installer/1.0")
                .build()
        ).execute()
        if (response.code == 403 || response.code == 429) throw Exception(context.getString(R.string.error_rate_limit))
        val body = response.body?.string() ?: throw Exception(context.getString(R.string.error_no_data))
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        return parseAppsConfig(body)
    }

    // Fetches the latest release JSON — exposed so callers can cache it per (owner/repo)
    // and avoid duplicate network calls for multiple apps from the same repository.
    fun fetchRelease(owner: String, repo: String): JsonObject {
        val response = client.newCall(
            Request.Builder()
                .url("$proxyBase/repos/$owner/$repo/releases/latest")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "BearApp-Installer/1.0")
                .build()
        ).execute()
        if (response.code == 403 || response.code == 429) throw Exception(context.getString(R.string.error_rate_limit))
        val body = response.body?.string() ?: throw Exception(context.getString(R.string.error_no_data))
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        return JsonParser.parseString(body).asJsonObject
    }

    // Parses an AppRelease from a pre-fetched release JSON using the first APK asset.
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

    // Parses an AppRelease from a pre-fetched release JSON by matching asset filename prefix.
    // e.g. filePrefix="YouTube" matches "YouTube_21_21_80.apk" → version "21.21.80"
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

    // Convenience wrappers that fetch + parse in one call (used by checkSelfUpdate).
    fun getLatestRelease(owner: String, repo: String): AppRelease = parseRelease(fetchRelease(owner, repo))

    private fun parseAppsConfig(json: String): AppsConfig {
        val obj = JsonParser.parseString(json).asJsonObject
        val appsArray = obj.getAsJsonArray("apps") ?: return AppsConfig(emptyList())
        val apps = appsArray.map { el ->
            val a = el.asJsonObject
            AppItem(
                id = a.get("id")?.asString ?: "",
                name = a.get("name")?.asString ?: "",
                version = a.get("version")?.asString ?: "",
                updatedAt = a.get("updated_at")?.asString ?: "",
                downloadUrl = a.get("download_url")?.asString ?: "",
                packageName = a.get("package_name")?.asString ?: "",
                iconUrl = a.get("icon_url")?.asString ?: "",
                description = a.get("description")?.asString ?: "",
                changelog = a.get("changelog")?.asString ?: "",
                githubOwner = a.get("github_owner")?.asString ?: "",
                githubRepo = a.get("github_repo")?.asString ?: "",
                githubFilePrefix = a.get("github_file_prefix")?.asString ?: ""
            )
        }
        return AppsConfig(apps)
    }
}
