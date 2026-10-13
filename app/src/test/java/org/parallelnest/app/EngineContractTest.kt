package org.parallelnest.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.parallelnest.app.engine.WorkProfileEngine
import org.parallelnest.app.engine.api.CloneEngine
import org.parallelnest.app.engine.api.NestServices

class EngineContractTest {
    @Test fun appFacingEngineDoesNotExposeConcreteWorkProfileImplementation() {
        assertEquals(CloneEngine::class.java, NestServices::class.java.getMethod("getEngine").returnType)
    }

    @Test fun profileOnlyOperationsKeepExplicitLegacyController() {
        assertEquals(WorkProfileEngine::class.java, NestServices::class.java.getMethod("getProfileEngine").returnType)
    }
}
