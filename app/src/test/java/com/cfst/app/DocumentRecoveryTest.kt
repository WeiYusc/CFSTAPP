package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.documents.DocumentOperationOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DocumentRecoveryTest {
    @Test fun previewGenerationSurvivesRealNewViewModel() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val first=SpeedTestViewModel(app)
        first.loadIpRanges("192.0.2.1"); val generation=first.sourceGeneration
        val next=SpeedTestViewModel(app)
        assertEquals(generation,next.sourceGeneration)
        next.previewDocument("192.0.2.2".toByteArray(),generation)
        assertNotNull(next.importPreview.value)
    }
    @Test fun deadlineDoesNotHoldOwnerLockWhileProviderCloseBlocks() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val constructor=DocumentOperationOwner::class.java.declaredConstructors.firstOrNull { it.parameterTypes.contentEquals(arrayOf(Application::class.java,java.lang.Long.TYPE)) }
        assertNotNull("soft deadline dependency required for real-owner tests",constructor)
        constructor!!.isAccessible=true
        val owner=constructor.newInstance(app,100L) as DocumentOperationOwner
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val uri=android.net.Uri.parse("content://test/blocked-close")
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStream(uri,object:InputStream() {
            override fun read():Int { entered.countDown(); release.await(5,TimeUnit.SECONDS); return -1 }
            override fun close() { release.await(5,TimeUnit.SECONDS) }
        })
        val id=owner.picker(0)!!; owner.read(id,uri); assertTrue(entered.await(2,TimeUnit.SECONDS))
        Thread.sleep(200)
        val returned=CountDownLatch(1); var reserved:String?="unset"
        val observer=Thread { reserved=owner.picker(1); returned.countDown() }.apply { start() }
        try {
            assertTrue("provider close cannot block UI owner lock",returned.await(300,TimeUnit.MILLISECONDS))
            assertNull(reserved); assertEquals(DocumentOperationOwner.Stage.QUARANTINED,owner.state.value!!.stage)
        } finally { release.countDown(); observer.join(1000) }
    }
}
