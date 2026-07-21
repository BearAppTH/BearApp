package app.bear.store.util

import java.io.File
import java.security.MessageDigest

/**
 * Verifies downloaded APKs against the SHA-256 digest GitHub reports for a
 * release asset (the "digest" field on the asset object, formatted as
 * "sha256:<hex>"). This guards against a corrupted or tampered download
 * making it to install — the file is compared against what GitHub says it
 * should be *before* PackageInstaller ever sees it.
 *
 * If GitHub doesn't provide a digest for a given asset (older releases,
 * or a source not covered by the digest rollout), verification is skipped
 * rather than treated as a failure — absence of a digest is not evidence
 * of tampering, just missing metadata.
 */
object ChecksumUtils {

    /** Streams the file to avoid loading large APKs fully into memory. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } >= 0) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns true if [expectedDigest] is missing/unparsable (nothing to
     * check against) OR the file's actual SHA-256 matches it.
     * Returns false only on a confirmed mismatch — the caller should treat
     * that as a corrupted/tampered download and refuse to install it.
     */
    fun verify(file: File, expectedDigest: String?): Boolean {
        val expectedHex = expectedDigest
            ?.substringAfter("sha256:", missingDelimiterValue = "")
            ?.trim()
            ?.lowercase()
        if (expectedHex.isNullOrBlank()) return true
        return sha256Hex(file).lowercase() == expectedHex
    }
}
