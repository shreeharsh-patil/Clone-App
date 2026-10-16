package org.parallelnest.app

import android.app.Application
import android.content.Context
import org.parallelnest.app.core.Registry
import org.parallelnest.app.engine.WorkProfileEngine
import org.parallelnest.app.engine.api.CloneEngine
import org.parallelnest.app.engine.api.NestServices

class NestApplication : Application(), NestServices {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        SelectedRuntime.attach(this)
    }
    override fun onCreate() {
        super.onCreate()
        SelectedRuntime.start()
    }
    override val registry by lazy { Registry(this) }
    override val profileEngine by lazy { WorkProfileEngine(this, registry) }
    override val engine: CloneEngine by lazy { SelectedRuntime.engine(this, registry) }
}
