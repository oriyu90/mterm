package dev.studiorizi.mterm.core.linux_core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootfsPathMapperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun mapper(): Triple<RootfsPathMapper, File, File> {
        val rootfs = tmp.newFolder("rootfs")
        val bridge = tmp.newFolder("bridge")
        return Triple(RootfsPathMapper(rootfs, bridge), rootfs, bridge)
    }

    @Test
    fun `normal guest maps under rootfs`() {
        val (mapper, rootfs) = mapper().let { it.first to it.second }
        val result = mapper.guestToHost("/etc/hosts")
        assertTrue(result.isSuccess)
        assertEquals(File(rootfs, "etc/hosts").absolutePath, result.getOrThrow().absolutePath)
    }

    @Test
    fun `dot segments normalize`() {
        val (mapper, rootfs) = mapper().let { it.first to it.second }
        val result = mapper.guestToHost("/usr/./bin")
        assertTrue(result.isSuccess)
        assertEquals(File(rootfs, "usr/bin").absolutePath, result.getOrThrow().absolutePath)
    }

    @Test
    fun `inner dotdot stays inside root`() {
        val (mapper, rootfs) = mapper().let { it.first to it.second }
        val result = mapper.guestToHost("/a/b/../c")
        assertTrue(result.isSuccess)
        assertEquals(File(rootfs, "a/c").absolutePath, result.getOrThrow().absolutePath)
    }

    @Test
    fun `dotdot escape rejected`() {
        val (mapper) = mapper().let { Triple(it.first, it.second, it.third) }
        assertTrue(mapper.guestToHost("/../etc/passwd").isFailure)
        assertTrue(mapper.guestToHost("/a/../../etc").isFailure)
    }

    @Test
    fun `relative guest rejected`() {
        val (mapper) = mapper().let { Triple(it.first, it.second, it.third) }
        assertTrue(mapper.guestToHost("etc/hosts").isFailure)
    }

    @Test
    fun `null byte rejected`() {
        val (mapper) = mapper().let { Triple(it.first, it.second, it.third) }
        assertTrue(mapper.guestToHost("/etc/passwd\u0000").isFailure)
    }

    @Test
    fun `empty guest rejected`() {
        val (mapper) = mapper().let { Triple(it.first, it.second, it.third) }
        assertTrue(mapper.guestToHost("").isFailure)
    }

    @Test
    fun `root maps to rootfs dir`() {
        val (mapper, rootfs) = mapper().let { it.first to it.second }
        val result = mapper.guestToHost("/")
        assertTrue(result.isSuccess)
        assertEquals(rootfs.absolutePath, result.getOrThrow().absolutePath)
    }

    @Test
    fun `hostToGuest round trip`() {
        val (mapper, rootfs) = mapper().let { it.first to it.second }
        val host = File(rootfs, "home/user/.zshrc")
        assertEquals("/home/user/.zshrc", mapper.hostToGuest(host))
    }

    @Test
    fun `hostToGuest outside returns null`() {
        val (mapper) = mapper().let { Triple(it.first, it.second, it.third) }
        val outside = tmp.newFolder("elsewhere")
        assertNull(mapper.hostToGuest(File(outside, "x")))
    }

    @Test
    fun `hostToGuest bridge maps to prefix`() {
        val (mapper, _, bridge) = mapper()
        assertEquals(
            "${RootfsPathMapper.BRIDGE_GUEST_PREFIX}/docs/a.txt",
            mapper.hostToGuest(File(bridge, "docs/a.txt")),
        )
    }
}
