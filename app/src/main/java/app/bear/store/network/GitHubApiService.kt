package app.bear.store.network

import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import app.bear.store.model.AppItem
import app.bear.store.model.AppRelease
import app.bear.store.model.AppsConfig
import java.util.concurrent.TimeUnit

class GitHubApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun getAppsConfig(owner: String, repo: String, branch: String = "main"): AppsConfig {
        val url = "https://raw.githubusercontent.com/$owner/$repo/$branch/apps_config.json"
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "BearApp-Installer/1.0")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("ไม่ได้รับข้อมูล")
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

        return parseAppsConfig(body)
    }

    fun getLatestRelease(owner: String, repo: String): AppRelease {
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "BearApp-Installer/1.0")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("ไม่ได้รับข้อมูล")
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        val obj = JsonParser.parseString(body).asJsonObject
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
                iconUrl = a.get("icon_url")?.asString ?: ""
            )
        }
        return AppsConfig(apps)
    }
}
