package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.documents.DocumentOperationOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DocumentOperationLifecycleTest {
    private fun await(condition:()->Boolean) { val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); while(!condition() && System.nanoTime()<deadline) Thread.sleep(10); assertTrue(condition()) }
    @Test fun closeFailureNeverPublishesSuccessAndRetryKeepsSource() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val owner=DocumentOperationOwner.get(app)
        val id=owner.prepareExport(0,"frozen")!!
        val uri=android.net.Uri.parse("content://test/close-fail")
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerOutputStream(uri,object:ByteArrayOutputStream() { override fun close() { throw IOException("close failed") } })
        owner.write(id,uri)
        await { owner.state.value?.stage==DocumentOperationOwner.Stage.FAILED }
        assertTrue(java.io.File(app.filesDir,"document-operations/$id.source").exists())
        val retry=owner.javaClass.methods.firstOrNull { it.name=="retryExport" }
        assertNotNull("explicit reselect retry missing",retry)
        val next=retry!!.invoke(owner,id) as String
        assertNotEquals(id,next)
    }
    @Test fun duplicateResultDoesNotRepeatWrite() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val id=owner.prepareExport(0,"frozen")!!; val uri=android.net.Uri.parse("content://test/dedup")
        val output=ByteArrayOutputStream(); org.robolectric.Shadows.shadowOf(app.contentResolver).registerOutputStream(uri,output)
        owner.write(id,uri); owner.write(id,uri)
        await { owner.state.value?.stage==DocumentOperationOwner.Stage.DONE }
        assertEquals("frozen",output.toString("UTF-8"))
        assertFalse(java.io.File(app.filesDir,"document-operations/$id.source").exists())
    }
}
