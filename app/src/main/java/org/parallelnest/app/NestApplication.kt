package org.parallelnest.app

import android.app.Application
import org.parallelnest.app.core.Registry
import org.parallelnest.app.engine.WorkProfileEngine

class NestApplication : Application() {
    val registry by lazy { Registry(this) }
    val engine by lazy { WorkProfileEngine(this, registry) }
}
