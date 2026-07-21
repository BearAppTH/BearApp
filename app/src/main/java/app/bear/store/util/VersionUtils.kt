package app.bear.store.util

object VersionUtils {

    /**
     * True if [configVersion] should be considered newer than [installedVersion].
     *
     * Compares the dot-separated numeric core of each version (e.g. "1.3.0").
     * When the numeric cores are equal, a semver-style pre-release suffix
     * (a "-" segment, e.g. "1.3.0-beta") is used as a tiebreaker: a full
     * release outranks a pre-release with the same numeric core. Two versions
     * with the same numeric core and the same pre-release status are
     * considered equal (not newer).
     */
    fun isVersionNewer(configVersion: String, installedVersion: String): Boolean {
        if (configVersion.isBlank() || installedVersion.isBlank()) return false
        val newParts = numericParts(configVersion)
        val insParts = numericParts(installedVersion)
        if (newParts.isEmpty() || insParts.isEmpty()) return false
        for (i in 0 until maxOf(newParts.size, insParts.size)) {
            val n = newParts.getOrElse(i) { 0 }
            val ins = insParts.getOrElse(i) { 0 }
            if (n > ins) return true
            if (n < ins) return false
        }
        val newIsPreRelease = configVersion.contains('-')
        val insIsPreRelease = installedVersion.contains('-')
        return insIsPreRelease && !newIsPreRelease
    }

    private fun numericParts(version: String): List<Int> =
        version.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
}
