package app.bear.store.util

import java.io.File
import java.security.MessageDigest

/**
 * Utility class for security operations including hash verification
 */
object SecurityUtils {
    
    /**
     * Calculate SHA256 hash of a file
     * @param file The file to hash
     * @return Hexadecimal string representation of SHA256 hash, or null if error occurs
     */
    fun calculateSHA256(file: File): String? {
        return try {
            if (!file.exists()) return null
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } >= 0) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            AppLogger.e("Failed to calculate SHA256: ${e.message}", e)
            null
        }
    }
    
    /**
     * Verify file hash matches expected value (case-insensitive comparison)
     * @param file The file to verify
     * @param expectedHash The expected SHA256 hash (hex format)
     * @return True if hashes match, false otherwise
     */
    fun verifySHA256(file: File, expectedHash: String?): Boolean {
        if (expectedHash.isNullOrBlank()) return true // Skip verification if no expected hash provided
        val actualHash = calculateSHA256(file) ?: return false
        return actualHash.equals(expectedHash, ignoreCase = true)
    }
}
