package dev.studiorizi.mterm.core.rootfs_manager

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class RootfsKeysTest {

    @Test
    fun `embedded public key decodes to 32 bytes`() {
        val raw = RootfsKeys.publicKeyRaw32()
        assertEquals(32, raw!!.size)
        assertEquals(
            RootfsKeys.PUBLIC_KEY_BASE64,
            Base64.getEncoder().encodeToString(raw),
        )
    }
}
