package com.cfst.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.settings.AppStateStore
import com.cfst.app.documents.DocumentOperationOwner
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.*
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class P01ClosureActivityTest {
    private fun await(c:()->Boolean) { val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); while(!c() && System.nanoTime()<end) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }; assertTrue(c()) }
    private fun call(a:MainActivity,name:String) { val m=a.javaClass.declaredMethods.firstOrNull { it.name.startsWith(name) }; assertNotNull("production action $name missing",m); m!!.isAccessible=true; m.invoke(a) }
    private fun field(a:MainActivity,name:String):Any? {
        if(name=="recoveryRequired") return a.javaClass.getDeclaredMethod("getRecoveryRequired").apply { isAccessible=true }.invoke(a)
        return a.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(a)
    }
    @Test fun corruptAuthorityActivitySafValidBackupExplicitConfirmRebuildsNormalVmWithoutDeletingSource() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val store=AppStateStore(app); store.edit().putInt("testCount",47).commit(); val payload=store.exportState()
        val file=File(app.filesDir,"app-state.json"); file.writeText("{broken")
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible(); val activity=controller.get()
        assertEquals(true,field(activity,"recoveryRequired")); assertEquals("{broken",file.readText())
        call(activity,"requestStateRestore"); val launched=Shadows.shadowOf(activity).nextStartedActivityForResult; assertNotNull(launched)
        val uri=android.net.Uri.parse("content://test/recovery"); Shadows.shadowOf(app.contentResolver).registerInputStream(uri,ByteArrayInputStream(payload.toByteArray()))
        activity.activityResultRegistry.dispatchResult(launched.requestCode,Activity.RESULT_OK,Intent().setData(uri))
        val owner=DocumentOperationOwner.get(app); await { owner.state.value?.stage==DocumentOperationOwner.Stage.PREVIEW }; assertEquals("{broken",file.readText())
        call(activity,"confirmStateRestore"); await { field(activity,"recoveryRequired")==false }
        val vm=ViewModelProvider(activity,SpeedTestViewModel.createFactory(app))[SpeedTestViewModel::class.java]; assertEquals(47,vm.testCount.value); assertFalse(vm.isTesting.value)
        assertEquals(47,AppStateStore(app).getInt("testCount",0)); assertTrue("original corrupt source archived",File(app.filesDir,"state-recovery").walkTopDown().any { it.isFile && it.readText()=="{broken" })
        controller.pause().stop().destroy()
    }
    @Test fun corruptActivityCancelledOrMaliciousBackupCannotCreateNewAuthority() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val payload=AppStateStore(app).exportState(); val file=File(app.filesDir,"app-state.json"); file.writeText("{broken")
        val c=Robolectric.buildActivity(MainActivity::class.java).setup().visible(); val a=c.get(); assertEquals(true,field(a,"recoveryRequired"))
        call(a,"requestStateRestore"); var launch=Shadows.shadowOf(a).nextStartedActivityForResult; a.activityResultRegistry.dispatchResult(launch.requestCode,Activity.RESULT_CANCELED,null); assertEquals("{broken",file.readText())
        call(a,"requestStateRestore"); launch=Shadows.shadowOf(a).nextStartedActivityForResult; val uri=android.net.Uri.parse("content://test/bad-recovery"); val malicious=payload.replace("\"testCount\":{\"type\":\"int\",\"value\":30}","\"testCount\":{\"type\":\"int\",\"value\":0}")
        Shadows.shadowOf(app.contentResolver).registerInputStream(uri,ByteArrayInputStream(malicious.toByteArray())); a.activityResultRegistry.dispatchResult(launch.requestCode,Activity.RESULT_OK,Intent().setData(uri))
        val owner=DocumentOperationOwner.get(app); await { owner.state.value?.stage==DocumentOperationOwner.Stage.FAILED }; assertEquals("{broken",file.readText()); assertEquals(true,field(a,"recoveryRequired")); c.pause().stop().destroy()
    }
    @Test fun realActivityBlockedWriteCancelExitNewExportAndRecreationRemainReusable() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup().visible(); val a=c.get(); call(a,"requestCsvExport"); await { Shadows.shadowOf(a).peekNextStartedActivityForResult()!=null }; val launch=Shadows.shadowOf(a).nextStartedActivityForResult
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val uri=android.net.Uri.parse("content://test/blocked-write"); val output=object:OutputStream() { override fun write(b:Int) { entered.countDown(); release.await(8,TimeUnit.SECONDS) }; override fun write(b:ByteArray,off:Int,len:Int) { write(0) }; override fun close() {} }
        Shadows.shadowOf(a.contentResolver).registerOutputStream(uri,output); a.activityResultRegistry.dispatchResult(launch.requestCode,Activity.RESULT_OK,Intent().setData(uri)); assertTrue(entered.await(3,TimeUnit.SECONDS))
        val owner=DocumentOperationOwner.get(a.application); owner.cancelCurrent(); assertEquals(DocumentOperationOwner.Stage.QUARANTINED,owner.state.value!!.stage); call(a,"requestCsvExport"); assertEquals(DocumentOperationOwner.Stage.QUARANTINED,owner.state.value!!.stage)
        release.countDown(); await { owner.state.value?.stage==DocumentOperationOwner.Stage.CANCELLED }; await { field(a,"pendingCsvId")==null && field(a,"pendingDocumentExportId")==null }
        assertFalse(File(a.filesDir,"pending-csv/${owner.state.value!!.id}.csv").exists()); call(a,"requestCsvExport"); await { Shadows.shadowOf(a).peekNextStartedActivityForResult()!=null }; val second=Shadows.shadowOf(a).nextStartedActivityForResult
        val state=Bundle(); c.saveInstanceState(state).pause().stop().destroy(); val nc=Robolectric.buildActivity(MainActivity::class.java).create(state).start().restoreInstanceState(state).resume().visible(); val next=nc.get()
        val good=android.net.Uri.parse("content://test/second"); val saved=ByteArrayOutputStream(); Shadows.shadowOf(next.contentResolver).registerOutputStream(good,saved)
        next.activityResultRegistry.dispatchResult(second.requestCode,Activity.RESULT_OK,Intent().setData(good)); await { owner.state.value?.stage==DocumentOperationOwner.Stage.DONE }; assertTrue(saved.size()>0); await { field(next,"pendingCsvId")==null }; nc.pause().stop().destroy()
    }
}
