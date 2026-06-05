package app.bear.store.model

sealed class SelfUpdateState {
    object Checking : SelfUpdateState()
    object UpToDate : SelfUpdateState()
    data class UpdateAvailable(val tagName: String, val downloadUrl: String, val changelog: String = "") : SelfUpdateState()
    data class Downloading(val progress: Int) : SelfUpdateState()
    object ReadyToInstall : SelfUpdateState()
    data class Error(val msg: String) : SelfUpdateState()
}
