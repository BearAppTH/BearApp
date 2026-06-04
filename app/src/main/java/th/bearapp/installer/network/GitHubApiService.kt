package th.bearapp.installer.network

import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import th.bearapp.installer.model.AppItem
import th.bearapp.installer.model.AppsConfig
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
                downloadUrl = a.get("download_url")?.asString ?: ""
            )
        }
        return AppsConfig(apps)
    }
}
