package app.bear.store.model

data class AppRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val apkUrl: String?,
    val apkSize: Long = 0L
)
