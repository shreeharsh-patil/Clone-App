package org.parallelnest.app.core
import org.parallelnest.app.engine.virtualization.VirtualContainerEngine

import org.junit.Assert.*
import org.junit.Test

class EnginePolicyTest {
    @Test fun virtualEngineNeverClaimsAvailability() {
        assertFalse(VirtualContainerEngine.verified)
        assertTrue(VirtualContainerEngine.reason.contains("one additional copy"))
    }
}
