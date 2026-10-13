package org.parallelnest.app

import android.app.Application
import org.parallelnest.app.core.Registry
import org.parallelnest.app.engine.WorkProfileEngine
import org.parallelnest.app.engine.api.CloneEngine
import org.parallelnest.app.engine.api.NestServices

class NestApplication : Application(), NestServices {
    override val registry by lazy { Registry(this) }
    override val profileEngine by lazy { WorkProfileEngine(this, registry) }
    override val engine: CloneEngine get() = profileEngine
}
