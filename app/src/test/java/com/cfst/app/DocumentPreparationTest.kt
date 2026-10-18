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
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DocumentPreparationTest {
    @Test fun prepareWritesSnapshotOnSameWorkerNotCallingThread() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val method=owner.javaClass.methods.firstOrNull { it.name=="prepareExportAsync" && it.parameterCount==3 }
        assertNotNull("private snapshot writes must not block UI",method)
        val done=CountDownLatch(1); var id:String?=null
        method!!.invoke(owner,0L,"frozen",{ value:String?, error:String? -> id=value; assertNull(error); done.countDown(); Unit })
        assertTrue(done.await(2,TimeUnit.SECONDS)); assertNotNull(id)
        val snapshot=owner.state.value!!.snapshot!!
        assertEquals("frozen",File(app.filesDir,"document-operations/$snapshot").readText())
        owner.cancelPicker(id)
    }
    @Test fun processRecoveryPreservesKindForExplicitRetry() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val id=owner.prepareExport(0,"frozen")!!
        val setter=owner.javaClass.methods.firstOrNull { it.name=="setExportKind" }; assertNotNull(setter)
        setter!!.invoke(owner,id,"STATE")
        val constructor=owner.javaClass.declaredConstructors.first { it.parameterTypes.contentEquals(arrayOf(Application::class.java,java.lang.Long.TYPE)) }.apply {isAccessible=true}
        val recovered=constructor.newInstance(app,30000L) as DocumentOperationOwner
        assertEquals("STATE",recovered.state.value!!.javaClass.getMethod("getKind").invoke(recovered.state.value))
    }
}
