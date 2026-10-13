package org.parallelnest.app.core

data class AppInfo(val packageName: String, val label: String, val paths: List<String>, val supported: Boolean = true, val reason: String = "Install in a separate Android work profile", val system: Boolean = false)
data class CloneInfo(val id: String, val packageName: String, val name: String, val profile: Long, val created: Long, val lastLaunch: Long = 0, val accent: Int = 0, val number: Int = 1, val engine: String = "work_profile", val suspended: Boolean = false, val paused: Boolean = false)
data class OperationResult(val success: Boolean, val message: String)
data class ProfileState(val managed: Boolean, val supported: Boolean, val canProvision: Boolean, val hasOtherProfile: Boolean, val bridgeAvailable: Boolean, val description: String)
