package th.bearapp.installer.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import th.bearapp.installer.model.AppRelease
import java.util.concurrent.TimeUnit

class GitHubApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun getLatestRelease(owner: String, repo: String): AppRelease {
        // Try GitHub Releases API first
        val releasesUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val request = Request.Builder()
            .url(releasesUrl)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "BearApp-Installer/1.0")
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw Exception("No response from server")

        if (!response.isSuccessful) {
            if (response.code == 404) {
                // Try fallback config file
                return getFromConfigFile(owner, repo)
            }
            throw Exception("Server error ${response.code}")
        }

        return parseReleaseJson(bodyStr)
    }

    private fun getFromConfigFile(owner: String, repo: String): AppRelease {
        val configUrl = "https://raw.githubusercontent.com/$owner/$repo/main/download_config.json"
        val request = Request.Builder()
            .url(configUrl)
            .header("User-Agent", "BearApp-Installer/1.0")
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw Exception("ไม่พบการกำหนดค่าการดาวน์โหลด")

        if (!response.isSuccessful) {
            throw Exception("ยังไม่มี release บน GitHub กรุณาสร้าง release ก่อน")
        }

        val json = JsonParser.parseString(bodyStr).asJsonObject
        return AppRelease(
            tagName = json.get("latest_version")?.asString ?: "latest",
            name = json.get("app_name")?.asString ?: "BearApp",
            body = json.get("changelog")?.asString ?: "",
            publishedAt = json.get("date")?.asString ?: "",
            apkUrl = json.get("download_url")?.asString,
            apkSize = json.get("size_bytes")?.asLong ?: 0L
        )
    }

    private fun parseReleaseJson(json: String): AppRelease {
        val obj = JsonParser.parseString(json).asJsonObject
        val tagName = obj.get("tag_name")?.asString ?: "unknown"
        val name = obj.get("name")?.asString ?: tagName
        val body = obj.get("body")?.asString ?: ""
        val publishedAt = formatDate(obj.get("published_at")?.asString ?: "")

        var apkUrl: String? = null
        var apkSize = 0L
        val assets = obj.getAsJsonArray("assets")
        assets?.forEach { el ->
            val asset = el.asJsonObject
            val assetName = asset.get("name")?.asString ?: ""
            if (assetName.endsWith(".apk")) {
                apkUrl = asset.get("browser_download_url")?.asString
                apkSize = asset.get("size")?.asLong ?: 0L
            }
        }

        return AppRelease(tagName, name, body, publishedAt, apkUrl, apkSize)
    }

    private fun formatDate(iso: String): String {
        return try {
            iso.substringBefore("T")
        } catch (e: Exception) {
            iso
        }
    }
}
