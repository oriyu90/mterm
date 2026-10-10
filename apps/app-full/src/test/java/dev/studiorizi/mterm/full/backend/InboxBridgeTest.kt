package dev.studiorizi.mterm.full.backend

import android.content.Context
import android.net.Uri
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class InboxBridgeTest {

    @Test
    fun importViewed_copiesStreamToInbox() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://com.example/shared/hello.txt")
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream("hello-inbox\n".toByteArray()),
        )
        val out = InboxBridge.importViewed(context, uri).getOrThrow()
        assertTrue(out.absolutePath.contains("shared/inbox"))
        assertEquals("hello-inbox\n", out.readText())
    }

    @Test
    fun importViewed_sanitizesTraversalName() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        // No DISPLAY_NAME query support in the shadow: falls back safely.
        val uri = Uri.parse("content://com.example/shared/%2E%2E%2Fevil.txt")
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream("x".toByteArray()),
        )
        val out = InboxBridge.importViewed(context, uri).getOrThrow()
        assertTrue(!out.name.contains("/") && !out.name.contains(".."))
        assertTrue(out.isFile)
    }

    @Test
    fun importViewed_missingStreamFails() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val result = InboxBridge.importViewed(
            context,
            Uri.parse("content://com.example/shared/gone.txt"),
        )
        assertTrue(result.isFailure)
    }
}
