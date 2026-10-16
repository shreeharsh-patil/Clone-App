package org.parallelnest.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.parallelnest.app.core.*
import java.util.UUID

/** Unknown/stale IDs must never delete another guest or historical metadata. */
class RecoveryDeletionTest {
    @Test fun staleGuestDeletionPreservesEveryRegisteredInstance() = runBlocking {
        val nest = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NestApplication
        val before = nest.registry.snapshot()
        val stale = CloneInfo(UUID.randomUUID().toString(),"org.parallelnest.probe","Stale",0,1,engine="virtual",virtualUser=0,runtime="ready",compatibility="probe_only",abi="bytecode")
        assertTrue(runCatching { nest.engine.deleteInstance(stale) }.isFailure)
        assertEquals(before,nest.registry.snapshot())
    }
}
