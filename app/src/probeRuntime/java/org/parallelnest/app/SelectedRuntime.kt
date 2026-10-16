package org.parallelnest.app

import android.app.Application
import org.parallelnest.app.core.Registry
import org.parallelnest.app.engine.api.CloneEngine
import org.parallelnest.app.engine.virtualization.VirtualContainerEngine

/** Default release keeps the previously verified first-party runtime. */
object SelectedRuntime {
    fun attach(application: Application) = Unit
    fun start() = Unit
    fun engine(application: Application, registry: Registry): CloneEngine = VirtualContainerEngine(application, registry)
}
