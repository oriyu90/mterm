package dev.studiorizi.mterm.core.storage_mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageMirrorManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun defaultExcludes() {
        assertTrue(SyncExcludes.isExcluded("proj/node_modules/react/index.js"))
        assertTrue(SyncExcludes.isExcluded("node_modules/a.js"))
        assertTrue(SyncExcludes.isExcluded(".git/config"))
        assertTrue(SyncExcludes.isExcluded("a/.git/objects/x"))
        assertTrue(SyncExcludes.isExcluded("build/output.o"))
        assertTrue(SyncExcludes.isExcluded(".venv/lib/python3.13/x.py"))
        assertTrue(SyncExcludes.isExcluded("downloads/rootfs.tar.zst.part"))
        assertTrue(SyncExcludes.isExcluded("file.part"))
        assertFalse(SyncExcludes.isExcluded("src/Main.kt"))
        assertFalse(SyncExcludes.isExcluded("buildSrc/settings.gradle.kts"))
        assertFalse(SyncExcludes.isExcluded(""))
    }

    @Test
    fun traversalRejected() {
        val mgr = StorageMirrorManager(tmp.root)
        assertTrue(mgr.mapGuestToMirror("m1", "../evil.sh").isFailure)
        assertTrue(mgr.mapGuestToMirror("m1", "a/../../evil.sh").isFailure)
        assertTrue(mgr.mapGuestToMirror("m1", "/etc/passwd").isFailure)
        assertTrue(mgr.mapGuestToMirror("m1", "a/b\u0000c").isFailure)
        assertTrue(mgr.mapGuestToMirror("m1", "").isFailure)
        assertTrue(mgr.mapGuestToMirror("../escape", "ok.txt").isFailure)
    }

    @Test
    fun validMappingStaysUnderMirror() {
        val mgr = StorageMirrorManager(tmp.root)
        val file = mgr.mapGuestToMirror("m1", "docs/note.txt").getOrThrow()
        val expectedBase = java.io.File(java.io.File(tmp.root, "m1"), "mirror").canonicalFile
        assertTrue(file.canonicalFile.path.startsWith(expectedBase.path))
    }

    @Test
    fun conflictWhenBothChanged() {
        val mgr = StorageMirrorManager(tmp.root)
        val conflict = mgr.detectConflict(
            androidMtime = 200L,
            linuxMtime = 300L,
            lastSync = 100L,
            path = "note.txt",
        )
        assertNotNull(conflict)
        assertEquals("note.txt", conflict!!.path)
    }

    @Test
    fun noConflictWhenOnlyOneSideChanged() {
        val mgr = StorageMirrorManager(tmp.root)
        assertNull(mgr.detectConflict(androidMtime = 200L, linuxMtime = 100L, lastSync = 100L))
        assertNull(mgr.detectConflict(androidMtime = 100L, linuxMtime = 200L, lastSync = 100L))
        assertNull(mgr.detectConflict(androidMtime = 50L, linuxMtime = 50L, lastSync = 100L))
        // Both touched but identical mtime: not a conflict.
        assertNull(mgr.detectConflict(androidMtime = 200L, linuxMtime = 200L, lastSync = 100L))
    }

    @Test
    fun journalRoundtrip() {
        val mgr = StorageMirrorManager(tmp.root)
        assertTrue(mgr.listJournal("m1").isEmpty())
        mgr.recordJournal("m1", SyncJournalEntry("a.txt", 3, 10L, null, "import", 1L))
        mgr.recordJournal("m1", SyncJournalEntry("b.txt", 5, 20L, "abc", "export", 2L))
        val entries = mgr.listJournal("m1")
        assertEquals(2, entries.size)
        assertEquals("a.txt", entries[0].relativePath)
        assertEquals("abc", entries[1].sha256)
    }

    @Test
    fun journalSkipsCorruptLines() {
        val mgr = StorageMirrorManager(tmp.root)
        mgr.recordJournal("m1", SyncJournalEntry("a.txt", 3, 10L, null, "import", 1L))
        val file = mgr.journalFile("m1")
        file.appendText("THIS IS NOT JSON\n{\"incomplete\":\n\n")
        mgr.recordJournal("m1", SyncJournalEntry("b.txt", 5, 20L, null, "import", 2L))
        val entries = mgr.listJournal("m1")
        assertEquals(listOf("a.txt", "b.txt"), entries.map { it.relativePath })
    }

    @Test
    fun journalRotatesPastCap() {
        val mgr = StorageMirrorManager(tmp.root)
        val total = StorageMirrorManager.JOURNAL_MAX_LINES +
            StorageMirrorManager.JOURNAL_ROTATE_SLACK + 10
        for (i in 0 until total) {
            mgr.recordJournal("m1", SyncJournalEntry("f$i.txt", i.toLong(), i.toLong(), null, "import", i.toLong()))
        }
        val entries = mgr.listJournal("m1")
        // Rotation trims to the cap when crossed, then appends resume:
        // bounded well under the untrimmed total, newest entry intact.
        assertTrue(entries.size < total)
        assertTrue(entries.size <= StorageMirrorManager.JOURNAL_MAX_LINES + StorageMirrorManager.JOURNAL_ROTATE_SLACK)
        assertEquals("f${total - 1}.txt", entries.last().relativePath)
    }
}
