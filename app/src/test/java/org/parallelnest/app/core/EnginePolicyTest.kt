package org.parallelnest.app.core
import org.parallelnest.app.engine.virtualization.VirtualContainerEngine

import org.junit.Assert.*
import org.junit.Test

class EnginePolicyTest {
    @Test fun prototypeExplicitlyDeclaresUnsupportedGeneralApps() {
        assertTrue(VirtualContainerEngine.reason.contains("prototype"))
        assertTrue(VirtualContainerEngine.reason.contains("unsupported"))
        assertTrue(VirtualContainerEngine.reason.contains("host UID"))
    }
}
