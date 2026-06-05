package app.bear.store.network

import android.content.Context
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

    fun getAppsConfig(owner: String, repo: String, branch: String = "main"): AppsConfig {
        val url = "https://raw.githubusercontent.com/$owner/$repo/$branch/apps_config.json"
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "BearApp-Installer/1.0")
            .build()
        val response = client.newCall(request).execute()
        if (response.code == 403 || response.code == 429) throw Exception(context.getString(R.string.error_rate_limit))
        val body = response.body?.string() ?: throw Exception(context.getString(R.string.error_no_data))
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        return parseAppsConfig(body)
    }

    fun getLatestRelease(owner: String, repo: String): AppRelease {
        val obj = fetchReleaseJson(owner, repo)
        val assets = obj.getAsJsonArray("assets")
        val apkUrl = assets?.firstOrNull { el ->
            el.asJsonObject.get("name")?.asString?.endsWith(".apk") == true
        }?.asJsonObject?.get("browser_download_url")?.asString
        return AppRelease(
            tagName = obj.get("tag_name")?.asString ?: "",
            name = obj.get("name")?.asString ?: "",
            body = obj.get("body")?.asString ?: "",
            publishedAt = obj.get("published_at")?.asString ?: "",
            apkUrl = apkUrl
        )
    }

    // Finds a release asset by filename prefix (e.g. "YouTube" matches "YouTube_21_21_80.apk").
    // Version is extracted by stripping the prefix + underscore and extension, then replacing
    // remaining underscores with dots: "21_21_80" → "21.21.80".
    fun getLatestReleaseAsset(owner: String, repo: String, filePrefix: String): AppRelease? {
        val obj = fetchReleaseJson(owner, repo)
        val assets = obj.getAsJsonArray("assets") ?: return null
        val prefix = "${filePrefix}_"
        val asset = assets.firstOrNull { el ->
            val name = el.asJsonObject.get("name")?.asString ?: ""
            name.startsWith(prefix) && name.endsWith(".apk")
        }?.asJsonObject ?: return null
        val assetName = asset.get("name")?.asString ?: return null
        val downloadUrl = asset.get("browser_download_url")?.asString ?: return null
        val version = assetName.removePrefix(prefix).removeSuffix(".apk").replace("_", ".")
        return AppRelease(
            tagName = version,
            name = obj.get("name")?.asString ?: "",
            body = obj.get("body")?.asString ?: "",
            publishedAt = obj.get("published_at")?.asString ?: "",
            apkUrl = downloadUrl
        )
    }

    private fun fetchReleaseJson(owner: String, repo: String) =
        client.newCall(
            Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/releases/latest")
                .header("Accept", "application/vnd.github.v3+json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "BearApp-Installer/1.0")
                .build()
        ).execute().let { response ->
            if (response.code == 403 || response.code == 429) throw Exception(context.getString(R.string.error_rate_limit))
            val body = response.body?.string() ?: throw Exception(context.getString(R.string.error_no_data))
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            JsonParser.parseString(body).asJsonObject
        }

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
