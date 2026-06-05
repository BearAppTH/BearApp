package app.bear.store.util

object VersionUtils {
    fun isVersionNewer(configVersion: String, installedVersion: String): Boolean {
        if (configVersion.isBlank() || installedVersion.isBlank()) return false
        val newParts = configVersion.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val insParts = installedVersion.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        if (newParts.isEmpty() || insParts.isEmpty()) return false
        for (i in 0 until maxOf(newParts.size, insParts.size)) {
            val n = newParts.getOrElse(i) { 0 }
            val ins = insParts.getOrElse(i) { 0 }
            if (n > ins) return true
            if (n < ins) return false
        }
        return false
    }
}
