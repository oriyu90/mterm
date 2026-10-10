package dev.studiorizi.mterm.full.backend

import android.net.Uri
import dev.studiorizi.mterm.full.backend.SafSyncEngine.mountIdFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SafSyncEngineTest {

    @Test
    fun mountIdFor_primaryTree() {
        assertEquals(
            "primary-Shared",
            mountIdFor(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AShared")),
        )
    }

    @Test
    fun mountIdFor_sanitizesSeparators() {
        val id = mountIdFor(Uri.parse("content://com.example/tree/a%3Ab%2Fc"))
        assertTrue(id.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun mountIdFor_emptyFallsBack() {
        assertEquals("shared", mountIdFor(Uri.parse("content://com.example/tree/")))
    }

    @Test
    fun mountIdFor_boundedLength() {
        val long = "x".repeat(200)
        val id = mountIdFor(Uri.parse("content://com.example/tree/$long"))
        assertTrue(id.length <= 48)
    }
}
