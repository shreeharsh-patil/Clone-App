package org.parallelnest.app.engine.api

import org.parallelnest.app.core.*
import org.parallelnest.app.engine.WorkProfileEngine

enum class CreationStage(val description: String) {
    CHECKING("Checking compatibility"), PREPARING("Preparing the work-profile request"), ANDROID("Waiting for Android installation and approval"), REGISTERED("Instance registered"), VERIFYING("Verifying availability")
}
interface CloneEngine {
    fun status(): ProfileState
    suspend fun listSupportedApps(): List<AppInfo>
    suspend fun createInstance(app: AppInfo, name: String, progress: (CreationStage) -> Unit = {}): OperationResult
    suspend fun launchInstance(instance: CloneInfo): OperationResult
    suspend fun stopInstance(instance: CloneInfo): OperationResult
    suspend fun deleteInstance(instance: CloneInfo): OperationResult
    suspend fun getInstances(): List<CloneInfo>
}
/** The UI consumes engine-neutral clone operations. Profile-only operations remain on the legacy controller until the virtual runtime is verified. */
interface NestServices {
    val registry: Registry
    val engine: CloneEngine
    val profileEngine: WorkProfileEngine
}
