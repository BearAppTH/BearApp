package app.bear.store.model

data class AppRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val apkUrl: String?,
    val apkSize: Long = 0L,
    // GitHub's "digest" field on a release asset, e.g. "sha256:abcd1234...".
    // Null when GitHub hasn't computed/exposed a digest for this asset.
    val apkDigest: String? = null
)
