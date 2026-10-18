package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PendingCsvExportTest {
    @Test fun missingSnapshotFailsBeforeOpeningDestination() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val owner = PendingCsvExport(app)
        val uri = android.net.Uri.parse("content://test/missing")
        val output = java.io.ByteArrayOutputStream()
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerOutputStream(uri, output)
        for (id in listOf(null, "../bad", "00000000-0000-0000-0000-000000000000")) {
            try { owner.deliver(id, uri); fail("missing snapshot must fail") }
            catch (_: java.io.IOException) { }
        }
        assertEquals(0, output.size())
    }
    @Test fun repeatedPreparationNeverRetainsMoreThanEightSnapshots() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val owner = PendingCsvExport(app)
        repeat(12) { owner.prepare("snapshot-$it") }
        val directory = File(app.filesDir, "pending-csv")
        assertTrue(directory.listFiles()!!.size <= 8)
        assertTrue(directory.deleteRecursively())
    }

    @Test fun cancellationDeletesOnlyItsSnapshot() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val owner = PendingCsvExport(app)
        val first = owner.prepare("first")
        val second = owner.prepare("second")
        owner.discard(first)
        assertFalse(File(app.filesDir, "pending-csv/$first.csv").exists())
        assertEquals("second", File(app.filesDir, "pending-csv/$second.csv").readText())
        owner.discard(second)
    }
    @Test fun cleanupExpiresOldFilesButPreservesActivePickerAndBoundsRetainedFiles() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val owner = PendingCsvExport(app)
        val active = owner.prepare("active")
        val directory = File(app.filesDir, "pending-csv")
        File(directory, "$active.csv").setLastModified(1L)
        repeat(40) { File(directory, "orphan-$it.csv").writeText("orphan") }
        val expired = File(directory, "expired.csv").apply { writeText("expired"); setLastModified(1L) }
        owner.cleanup(active)
        assertFalse(expired.exists())
        assertEquals("active", File(directory, "$active.csv").readText())
        assertTrue(directory.listFiles()!!.size <= 8)
        owner.discard(active)
        assertTrue(directory.deleteRecursively())
    }
}
