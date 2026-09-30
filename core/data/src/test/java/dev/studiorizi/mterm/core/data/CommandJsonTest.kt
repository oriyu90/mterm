package dev.studiorizi.mterm.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM test: covers the commandJson helper without touching Room. */
class CommandJsonTest {
    @Test
    fun roundtrip() {
        val command = listOf("zsh", "-l", "-c", "echo hi")
        assertEquals(command, CommandJson.decode(CommandJson.encode(command)))
    }

    @Test
    fun emptyRoundtrip() {
        assertEquals(emptyList<String>(), CommandJson.decode(CommandJson.encode(emptyList())))
    }
}
