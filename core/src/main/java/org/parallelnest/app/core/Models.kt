package org.parallelnest.app.core

data class AppInfo(val packageName: String, val label: String, val paths: List<String>, val supported: Boolean = true, val reason: String = "First-party guest Activity prototype", val system: Boolean = false)
data class CloneInfo(val id: String, val packageName: String, val name: String, val profile: Long, val created: Long, val lastLaunch: Long = 0, val accent: Int = 0, val number: Int = 1, val engine: String = "work_profile", val suspended: Boolean = false, val paused: Boolean = false,
    val virtualUser: Long = -1, val runtime: String = "legacy", val compatibility: String = "legacy", val versionCode: Long = 0, val abi: String = "unknown") {
    val environmentKey: String get() = if (engine == "virtual") "virtual:$virtualUser" else "work_profile:$packageName:$profile"
}
data class OperationResult(val success: Boolean, val message: String, val instanceId: String? = null)
data class ProfileState(val managed: Boolean, val supported: Boolean, val canProvision: Boolean, val hasOtherProfile: Boolean, val bridgeAvailable: Boolean, val description: String)
data class EngineState(val type: String, val ready: Boolean, val supportsMultipleInstances: Boolean, val description: String, val capabilities: Map<String, String> = emptyMap())
