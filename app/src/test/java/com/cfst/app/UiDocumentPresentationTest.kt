package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.documents.DocumentOperationOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class UiDocumentPresentationTest {
    private fun model(owner:DocumentOperationOwner):Any {
        val type=try { Class.forName("com.cfst.app.ui.presentation.DocumentPresentationKt") } catch(_:ClassNotFoundException) { fail("document action mapping missing"); throw AssertionError() }
        return type.getMethod("documentPresentation",DocumentOperationOwner::class.java).invoke(null,owner)!!
    }
    private fun field(p:Any,name:String):Any=p.javaClass.getMethod("get$name").invoke(p)!!
    @Test fun realPickerCanCancelButNotRetryOrCreateSecondSlot() {
        val owner=DocumentOperationOwner.get(ApplicationProvider.getApplicationContext<Application>())
        val id=owner.picker(0)!!; val p=model(owner)
        assertEquals(true,field(p,"CanCancel")); assertEquals(false,field(p,"CanRetry"))
        assertTrue(field(p,"Label").toString().contains("选择")); assertNull(owner.picker(0))
        owner.cancelPicker(id); assertEquals(false,field(model(owner),"CanCancel"))
    }
    @Test fun failedReadWithoutSnapshotDoesNotOfferExportRetry() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val id=owner.picker(0)!!; owner.read(id,android.net.Uri.fromFile(File(app.filesDir,"absent")))
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while(owner.state.value?.stage==DocumentOperationOwner.Stage.READING && System.nanoTime()<deadline) Thread.sleep(10)
        assertEquals(DocumentOperationOwner.Stage.FAILED,owner.state.value!!.stage)
        val p=model(owner); assertEquals(false,field(p,"CanRetry")); assertEquals(false,field(p,"CanCancel"))
    }
    @Test fun stageMatrixNeverOffersNoOpCancelForApplyingOrQuarantine() {
        val type=try { Class.forName("com.cfst.app.ui.presentation.DocumentPresentationKt") } catch(_:ClassNotFoundException) { fail("document stage mapping missing"); throw AssertionError() }
        val method=type.getMethod("documentStagePresentation",DocumentOperationOwner.Stage::class.java,String::class.java,String::class.java,Boolean::class.javaPrimitiveType)
        for(stage in DocumentOperationOwner.Stage.values()) {
            val p=method.invoke(null,stage,"STATE","x".repeat(10000),false)!!
            val cancellable=stage in setOf(DocumentOperationOwner.Stage.PREPARING,DocumentOperationOwner.Stage.READY,DocumentOperationOwner.Stage.PICKER,DocumentOperationOwner.Stage.READING,DocumentOperationOwner.Stage.PREVIEW,DocumentOperationOwner.Stage.WRITING)
            assertEquals(stage.name,cancellable,field(p,"CanCancel")); assertTrue(field(p,"Message").toString().length<=512)
            if(stage==DocumentOperationOwner.Stage.QUARANTINED) assertTrue(field(p,"Label").toString().contains("隔离"))
            if(stage==DocumentOperationOwner.Stage.APPLYING) assertTrue(field(p,"Label").toString().contains("恢复"))
        }
    }
}
