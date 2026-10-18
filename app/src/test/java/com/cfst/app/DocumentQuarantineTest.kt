package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.documents.DocumentOperationOwner
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DocumentQuarantineTest {
    @Test fun blockedProviderQuarantinesSlotAndLateReadNeverPreviews() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val owner=DocumentOperationOwner.get(app)
        val entered=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
        val uri=android.net.Uri.parse("content://test/blocking")
        val input=object:InputStream() {
            override fun read():Int { entered.countDown(); release.await(5,TimeUnit.SECONDS); return -1 }
            override fun close() { }
        }
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStream(uri,input)
        val id=owner.picker(0)!!; owner.read(id,uri)
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        val cancel=owner.javaClass.methods.firstOrNull { it.name=="cancelOperation" }
        assertNotNull("quarantine owner API absent",cancel)
        cancel!!.invoke(owner,id)
        assertEquals(DocumentOperationOwner.Stage.QUARANTINED,owner.state.value!!.stage)
        assertNull(owner.picker(1))
        release.countDown()
        Thread.sleep(100)
        assertNotEquals(DocumentOperationOwner.Stage.PREVIEW,owner.state.value!!.stage)
    }
    @Test fun fullStateReadUsesSameOwnerButNotIpParser() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val vm=SpeedTestViewModel(app); val payload=vm.exportAppState()
        val uri=android.net.Uri.parse("content://test/state")
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStream(uri,ByteArrayInputStream(payload.toByteArray()))
        val id=owner.picker(0)!!
        val read=owner.javaClass.methods.firstOrNull { it.name=="readState" }
        assertNotNull("state restore read entry missing",read); read!!.invoke(owner,id,uri)
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(owner.state.value?.stage==DocumentOperationOwner.Stage.READING && System.nanoTime()<deadline) Thread.sleep(10)
        assertEquals(DocumentOperationOwner.Stage.PREVIEW,owner.state.value!!.stage)
    }
}
