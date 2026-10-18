package com.cfst.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.documents.DocumentOperationOwner
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import kotlinx.coroutines.launch
import java.util.concurrent.*
import java.io.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class P01DocumentRepairTest {
    private fun await(condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(!condition() && System.nanoTime()<deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("owner/Activity transition timed out",condition())
    }
    private fun invoke(activity:MainActivity,name:String) {
        activity.javaClass.declaredMethods.first { it.name.startsWith(name) }.apply { isAccessible=true }.invoke(activity)
    }
    private fun vm(activity:MainActivity)=ViewModelProvider(activity,SpeedTestViewModel.createFactory(activity.application))[SpeedTestViewModel::class.java]
    private fun preparing(kind:String) {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val worker=owner.javaClass.getDeclaredField("worker").apply { isAccessible=true }.get(owner) as ExecutorService
        val blocked=CountDownLatch(1); val release=CountDownLatch(1)
        worker.execute { blocked.countDown(); release.await(8,TimeUnit.SECONDS) }; assertTrue(blocked.await(2,TimeUnit.SECONDS))
        val first=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        invoke(first.get(),if(kind=="CSV") "requestCsvExport" else "requestStateExport")
        val id=owner.state.value!!.id
        val saved=Bundle(); first.pause().saveInstanceState(saved).stop().destroy()
        val second=Robolectric.buildActivity(MainActivity::class.java).create(saved).start().restoreInstanceState(saved).resume().visible()
        try {
            release.countDown()
            await { Shadows.shadowOf(second.get()).peekNextStartedActivityForResult()!=null }
            // Robolectric's started-activity queue is shared across Activity shadows.
            // Assert actual production claimant fields, not that shared queue on the old shadow.
            assertTrue(first.get().isDestroyed)
            val pending=MainActivity::class.java.getDeclaredField("pendingDocumentExportId").apply { isAccessible=true }
            assertNull("destroyed Activity must not claim",pending.get(first.get()))
            assertEquals(id,pending.get(second.get()))
            val launched=Shadows.shadowOf(second.get()).nextStartedActivityForResult
            assertNull("exactly one picker",Shadows.shadowOf(second.get()).peekNextStartedActivityForResult())
            val uri=android.net.Uri.parse("content://test/preparing-$kind")
            val output=ByteArrayOutputStream(); Shadows.shadowOf(app.contentResolver).registerOutputStream(uri,output)
            second.get().activityResultRegistry.dispatchResult(launched.requestCode,Activity.RESULT_OK,Intent().setData(uri))
            await { owner.state.value?.stage==DocumentOperationOwner.Stage.DONE }
            assertTrue(output.size()>0); assertNotNull("operation slot must be reusable",owner.picker(0))
        } finally { release.countDown(); second.pause().stop().destroy() }
    }
    @Test fun csvPreparationDestroyedActivityIsClaimedOnceByNewActivity()=preparing("CSV")
    @Test fun statePreparationDestroyedActivityIsClaimedOnceByNewActivity()=preparing("STATE")
    @Test fun familyChangeRejectsLateProviderPreviewAndAllowsNewPicker() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity=controller.get(); val vm=vm(activity); val owner=DocumentOperationOwner.get(activity.application)
        val id=owner.picker(vm.sourceGeneration)!!; val uri=android.net.Uri.parse("content://test/family-late")
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val stream=object:ByteArrayInputStream("192.0.2.1".toByteArray()) {
            override fun read(b:ByteArray,off:Int,len:Int):Int { entered.countDown(); release.await(8,TimeUnit.SECONDS); return super.read(b,off,len) }
        }
        Shadows.shadowOf(activity.contentResolver).registerInputStream(uri,stream)
        owner.read(id,uri); assertTrue(entered.await(3,TimeUnit.SECONDS)); vm.startTest(); assertFalse(vm.isTesting.value)
        vm.updateUseIpv6(true); release.countDown()
        await { !owner.blocksStart() }
        assertNull(vm.importPreview.value); assertNotNull(owner.picker(vm.sourceGeneration))
        controller.pause().stop().destroy()
    }
    @Test fun oldFieldHistoryExportsThroughActualActivityLauncherWithoutNullCrash() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val old="""[{"ipAddress":"192.0.2.17","packetsSent":1,"packetsReceived":1,"packetLoss":0,"avgLatency":1,"downloadSpeed":2,"regionCode":"N/A","testTime":1}]"""
        app.getSharedPreferences("app_prefs",0).edit().putString("history_records",com.google.gson.Gson().toJson(listOf(old))).commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible(); Shadows.shadowOf(Looper.getMainLooper()).idle()
        val method=controller.get().javaClass.declaredMethods.first { it.name=="requestHistoryExport" }.apply { isAccessible=true }
        method.invoke(controller.get(),0)
        await { Shadows.shadowOf(controller.get()).peekNextStartedActivityForResult()!=null }
        val launched=Shadows.shadowOf(controller.get()).nextStartedActivityForResult
        val output=ByteArrayOutputStream(); val uri=android.net.Uri.parse("content://test/old-export")
        Shadows.shadowOf(app.contentResolver).registerOutputStream(uri,output)
        controller.get().activityResultRegistry.dispatchResult(launched.requestCode,Activity.RESULT_OK,Intent().setData(uri))
        await { DocumentOperationOwner.get(app).state.value?.stage==DocumentOperationOwner.Stage.DONE }
        assertTrue(output.toString("UTF-8").contains("192.0.2.17")); assertTrue(output.toString("UTF-8").contains("UNKNOWN"))
        controller.pause().stop().destroy()
    }
    @Test fun generationChangeFinishesRealOwnerPreviewAndFreesNewImport() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity=controller.get(); val vm=vm(activity); val owner=DocumentOperationOwner.get(activity.application)
        val id=owner.picker(vm.sourceGeneration)!!
        val uri=android.net.Uri.parse("content://test/late-read"); val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val stream=object:ByteArrayInputStream("192.0.2.1".toByteArray()) {
            override fun read(b:ByteArray,off:Int,len:Int):Int { entered.countDown(); release.await(8,TimeUnit.SECONDS); return super.read(b,off,len) }
        }
        Shadows.shadowOf(activity.contentResolver).registerInputStream(uri,stream)
        owner.read(id,uri); assertTrue(entered.await(3,TimeUnit.SECONDS))
        vm.restoreBuiltIn(); release.countDown()
        await { owner.state.value?.stage !in setOf(DocumentOperationOwner.Stage.READING,DocumentOperationOwner.Stage.QUARANTINED) }
        assertNull(vm.importPreview.value)
        assertNotNull("invalidated import may not leave preview slot locked",owner.picker(vm.sourceGeneration))
        controller.pause().stop().destroy()
    }
    @Test fun cancelledPreparationDoesNotLaunchAndReleasesSlotAfterWorkerExits() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val worker=owner.javaClass.getDeclaredField("worker").apply { isAccessible=true }.get(owner) as ExecutorService
        val blocked=CountDownLatch(1); val release=CountDownLatch(1)
        worker.execute { blocked.countDown(); release.await(8,TimeUnit.SECONDS) }; assertTrue(blocked.await(2,TimeUnit.SECONDS))
        owner.prepareExportAsync(0,"csv","CSV")
        owner.cancelCurrent(); assertNull(owner.claimReady())
        release.countDown(); await { !owner.blocksStart() }
        assertEquals(DocumentOperationOwner.Stage.CANCELLED,owner.state.value!!.stage)
        assertNotNull(owner.picker(0))
    }
    @Test fun failedPreparingMetadataBeforeWorkerEnqueueDoesNotStrandBusySlot()=kotlinx.coroutines.runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val collector=launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            owner.state.collect { op -> if(op?.stage==DocumentOperationOwner.Stage.PREPARING) {
                val dir=File(app.filesDir,"document-operations")
                for(name in listOf("operation.json","operation.json.bak")) File(dir,name).apply { delete();mkdirs();resolve("block").writeText("block") }
            } }
        }
        try { owner.prepareExportAsync(0,"csv","CSV"); assertFalse(owner.blocksStart()); assertNull(owner.claimReady()) }
        finally { collector.cancel() }
    }
    @Test fun preparingTransitionMetadataFailureReleasesReservationWithoutWorker() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val dir=File(app.filesDir,"document-operations")
        // Reserve via valid durable picker first; make only PREPARING persistence fail.
        val worker=owner.javaClass.getDeclaredField("worker").apply { isAccessible=true }.get(owner) as ExecutorService
        val blocked=CountDownLatch(1); val release=CountDownLatch(1)
        worker.execute { blocked.countDown(); release.await(8,TimeUnit.SECONDS) }; assertTrue(blocked.await(2,TimeUnit.SECONDS))
        owner.prepareExportAsync(0,"csv","CSV")
        for(name in listOf("operation.json","operation.json.bak")) File(dir,name).apply { delete();mkdirs();resolve("block").writeText("block") }
        release.countDown(); await { !owner.blocksStart() }
        assertEquals(DocumentOperationOwner.Stage.FAILED,owner.state.value!!.stage)
        assertNull(owner.claimReady())
    }
    @Test fun preparationMetadataWriteFailureCannotPublishReadyOrLaunchPicker() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val dir=File(app.filesDir,"document-operations")
        for(name in listOf("operation.json","operation.json.bak")) File(dir,name).apply { delete();mkdirs();resolve("block").writeText("block") }
        owner.prepareExportAsync(0,"csv","CSV")
        await { owner.state.value?.stage!=DocumentOperationOwner.Stage.PREPARING }
        assertNotEquals(DocumentOperationOwner.Stage.READY,owner.state.value!!.stage)
        assertNull(owner.claimReady())
        assertNull("failed durable picker reservation must not launch",owner.picker(0))
    }
    @Test fun metadataFailureWhileProviderBlockedNeverReleasesQuarantineEarly() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val id=owner.picker(0)!!; val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val uri=android.net.Uri.parse("content://test/meta-block")
        val stream=object:InputStream() { override fun read():Int { entered.countDown(); release.await(8,TimeUnit.SECONDS); return -1 }; override fun close() {} }
        Shadows.shadowOf(app.contentResolver).registerInputStream(uri,stream)
        owner.read(id,uri); assertTrue(entered.await(3,TimeUnit.SECONDS))
        val dir=File(app.filesDir,"document-operations")
        for(name in listOf("operation.json","operation.json.bak")) File(dir,name).apply { delete();mkdirs();resolve("block").writeText("block") }
        owner.cancelOperation(id)
        try { assertNull("persist failure must not free active IO slot",owner.picker(1)); assertTrue(owner.blocksStart()) }
        finally { release.countDown() }
    }
    @Test fun exportedLargeStateCanBeReadBySameProductionSafOwner() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val state=com.cfst.app.settings.AppStateStore.get(app)
        // Bounded large legacy backup exercises the same package envelope without fake run data.
        assertTrue(state.edit().putString("legacy_backup","x".repeat(2200000)).commit())
        val payload=state.exportState(); val uri=android.net.Uri.parse("content://test/large-state")
        Shadows.shadowOf(app.contentResolver).registerInputStream(uri,ByteArrayInputStream(payload.toByteArray()))
        val id=owner.picker(0)!!; owner.readState(id,uri)
        await { owner.state.value?.stage!=DocumentOperationOwner.Stage.READING }
        assertEquals(DocumentOperationOwner.Stage.PREVIEW,owner.state.value!!.stage)
        assertEquals(payload,owner.preview(id)!!.toString(Charsets.UTF_8))
    }
    @Test fun pickerAndReadingGateStartBeforeAnyEngineReservation() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app); vm.loadIpRanges("127.0.0.1")
        val owner=DocumentOperationOwner.get(app); val id=owner.picker(vm.sourceGeneration)!!
        vm.startTest()
        assertFalse(vm.isTesting.value)
        val engine=vm.javaClass.getDeclaredField("engine").apply { isAccessible=true }.get(vm) as com.cfst.app.speedtest.SpeedTestEngine
        assertNull(engine.runOwner)
        owner.cancelPicker(id)
    }
}
