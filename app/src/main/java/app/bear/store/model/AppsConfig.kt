package app.bear.store.model

/**
 * Configuration model for apps list
 */
data class AppsConfig(
    val apps: List<AppItem> = emptyList()
) {
    /**
     * Validate the config - throw exception if invalid
     */
    fun validate() {
        apps.forEach { app ->
            require(app.id.isNotBlank()) { "App ID cannot be blank" }
            require(app.name.isNotBlank()) { "App name cannot be blank" }
            require(app.packageName.isNotBlank()) { "Package name cannot be blank" }
        }
    }
}
