package dev.studiorizi.mterm.core.root_core

import org.junit.Assert.assertNotNull
import org.junit.Test

class RootManagerTest {

    @Test
    fun capabilities_doesNotThrow() {
        val capabilities = RootManager().capabilities()
        assertNotNull(capabilities)
    }

    @Test
    fun detectSu_doesNotThrow() {
        // su is expected to be absent on JVM CI hosts; must return, not throw.
        RootManager().detectSu()
    }

    @Test
    fun isPrivateNamespaceSupported_doesNotThrow() {
        RootManager.isPrivateNamespaceSupported()
    }
}
