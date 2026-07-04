package app.bear.store.util

/**
 * Utility object for version comparison following semantic versioning
 */
object VersionUtils {
    
    /**
     * Check if configVersion is newer than installedVersion
     * Supports semantic versioning (e.g., 1.0.0, 1.0.0-rc1, 1.0.0-beta+build)
     * 
     * @param configVersion Version from configuration (e.g., "1.2.0")
     * @param installedVersion Currently installed version (e.g., "1.1.5")
     * @return True if configVersion is newer, false otherwise
     */
    fun isVersionNewer(configVersion: String, installedVersion: String): Boolean {
        if (configVersion.isBlank() || installedVersion.isBlank()) return false
        
        return try {
            val newVer = parseVersion(configVersion)
            val insVer = parseVersion(installedVersion)
            
            // Compare major.minor.patch
            when {
                newVer.major > insVer.major -> true
                newVer.major < insVer.major -> false
                newVer.minor > insVer.minor -> true
                newVer.minor < insVer.minor -> false
                newVer.patch > insVer.patch -> true
                newVer.patch < insVer.patch -> false
                // Same version numbers, compare pre-release
                else -> {
                    when {
                        newVer.prerelease.isNotBlank() && insVer.prerelease.isBlank() -> false // beta < release
                        newVer.prerelease.isBlank() && insVer.prerelease.isNotBlank() -> true // release > beta
                        newVer.prerelease.isNotBlank() && insVer.prerelease.isNotBlank() -> {
                            // Compare pre-release versions (alphanumeric)
                            comparePrerelease(newVer.prerelease, insVer.prerelease) > 0
                        }
                        else -> false // Exact match
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w("Version comparison error: ${e.message}")
            false
        }
    }
    
    private data class Version(
        val major: Int = 0,
        val minor: Int = 0,
        val patch: Int = 0,
        val prerelease: String = ""
    )
    
    private fun parseVersion(versionString: String): Version {
        // Remove 'v' or 'V' prefix if present
        var version = versionString.trimStart('v', 'V')
        
        // Extract pre-release part (after - or +)
        val prereleasePart = version.substringAfter('-').takeIf { version.contains('-') } ?: ""
        version = version.substringBefore('-').substringBefore('+')
        
        // Parse major.minor.patch
        val parts = version.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        
        return Version(
            major = parts.getOrElse(0) { 0 },
            minor = parts.getOrElse(1) { 0 },
            patch = parts.getOrElse(2) { 0 },
            prerelease = prereleasePart
        )
    }
    
    private fun comparePrerelease(pre1: String, pre2: String): Int {
        val parts1 = pre1.split(Regex("[.-]"))
        val parts2 = pre2.split(Regex("[.-]"))
        
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrNull(i) ?: ""
            val p2 = parts2.getOrNull(i) ?: ""
            
            val cmp = when {
                p1.isEmpty() && p2.isEmpty() -> 0
                p1.isEmpty() -> -1 // shorter is smaller
                p2.isEmpty() -> 1
                else -> {
                    val n1 = p1.toIntOrNull()
                    val n2 = p2.toIntOrNull()
                    when {
                        n1 != null && n2 != null -> n1.compareTo(n2)
                        n1 != null -> -1 // numeric < alphabetic
                        n2 != null -> 1
                        else -> p1.compareTo(p2)
                    }
                }
            }
            
            if (cmp != 0) return cmp
        }
        
        return 0
    }
}
