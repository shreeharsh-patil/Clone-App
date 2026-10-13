package org.parallelnest.app.core

data class AppInfo(val packageName: String, val label: String, val paths: List<String>, val supported: Boolean = true, val reason: String = "Install in a separate Android work profile")
data class CloneInfo(val id: String, val packageName: String, val name: String, val profile: Long, val created: Long, val lastLaunch: Long = 0, val locked: Boolean = false, val accent: Int = 0, val suspended: Boolean = false, val paused: Boolean = false)
data class OperationResult(val success: Boolean, val message: String)
data class ProfileState(val managed: Boolean, val supported: Boolean, val canProvision: Boolean, val hasOtherProfile: Boolean, val bridgeAvailable: Boolean, val description: String)

interface CloneEngine {
    fun status(): ProfileState
    suspend fun listSupportedApps(): List<AppInfo>
    suspend fun createInstance(app: AppInfo, name: String): OperationResult
    suspend fun launchInstance(instance: CloneInfo): OperationResult
    suspend fun stopInstance(instance: CloneInfo): OperationResult
    suspend fun deleteInstance(instance: CloneInfo): OperationResult
    suspend fun getInstances(): List<CloneInfo>
}

/** Intentionally unavailable. No engine or guest APK is bundled. */
object VirtualContainerEngine {
    const val verified = false
    const val reason = "Disabled: no engine has passed licensing, Android 13–16 compatibility and isolation verification. Work profiles support one additional copy per app."
}
