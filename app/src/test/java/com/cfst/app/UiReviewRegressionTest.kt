package com.cfst.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.model.*
import com.cfst.app.speedtest.*
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.ui.presentation.presentResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class UiReviewRegressionTest {
    private fun vm()=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>()).also { idle() }
    private fun idle()=Shadows.shadowOf(Looper.getMainLooper()).idle()
    @Test fun discardedQuickSnapshotCannotBeSavedOrReopenedAsDiscardedInput() {
        val vm=vm(); val before=vm.exportAppState(); val original=vm.beginDraft()
        val stale=original.copy(candidates="71"); vm.updateDraft(stale); vm.cancelDraft()
        assertFalse("discarded editor snapshot must not recreate the owner",vm.saveDraft(stale))
        assertEquals(before,vm.exportAppState()); assertEquals(original,vm.beginDraft())
    }
    @Test fun editableDraftIsObservedAndClearedByGlobalDiscard() {
        val vm=vm(); val getter=vm.javaClass.methods.firstOrNull { it.name=="getEditableDraft" }
        assertNotNull("quick editor must observe the real VM draft",getter)
        val flow=getter!!.invoke(vm) as StateFlow<*>
        val draft=vm.beginDraft().copy(candidates="71");vm.updateDraft(draft)
        assertEquals(draft,flow.value);vm.cancelDraft();assertNull(flow.value)
    }
    @Test fun untouchedOpenEditorCannotCommitOrEditDuringReservedRun() {
        val vm=vm(); vm.loadIpRanges("127.0.0.1");val original=vm.beginDraft();val before=vm.exportAppState()
        vm.startTest(); assertTrue(vm.isTesting.value)
        try {
            vm.updateDraft(original.copy(candidates="71"))
            assertFalse("running owner must reject save",vm.saveDraft(original.copy(candidates="71")))
            assertEquals(original,vm.beginDraft()); assertEquals(before,vm.exportAppState())
        } finally { vm.stopTest(); idle() }
    }
    @Test fun failedOrCancelledDownloadNeverBecomesPreferredInLatencyMode() {
        for(error in listOf(DownloadError.IO,DownloadError.CANCELLED)) {
            val row=SpeedTestResult("192.0.2.1",1,1,0.0,1.25,0.0,"N/A",probe=ProbeMeasurement(1,1,1.25),download=DownloadMeasurement(65536,1000000000,error=error,safetyVerified=true))
            assertEquals("异常/未达标",presentResult(row,SpeedTestEngine.TestConfig(downloadTest=false)).bucket)
        }
    }
    @Test fun completedDownloadResultsCannotBeReinterpretedByNewSavedSettings() {
        val vm=vm();vm.loadIpRanges("127.0.0.1");vm.startTest();vm.stopTest();idle()
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while(vm.isTesting.value && System.nanoTime()<deadline) { idle();Thread.sleep(10) }
        assertFalse(vm.isTesting.value)
        val row=SpeedTestResult("192.0.2.1",1,1,0.0,1.25,0.0,"N/A",probe=ProbeMeasurement(1,1,1.25))
        @Suppress("UNCHECKED_CAST") val results=vm.javaClass.getDeclaredField("_results").apply { isAccessible=true }.get(vm) as MutableStateFlow<List<SpeedTestResult>>
        results.value=listOf(row);vm.updateEnableDownloadTest(false)
        assertNull("probe-only row from download run is not a latency-mode recommendation",vm.getBestIp())
    }
    @Test fun latencyHistoryUsesFrozenModeAfterDurableReopen() {
        val app=ApplicationProvider.getApplicationContext<Application>();val vm=vm()
        val ip="192.0.2.1";val id=java.util.UUID.randomUUID().toString();val ledger=RunLedger(id,1,setOf(ip))
        ledger.accept(MeasurementEvent(EventIdentity(id,ip,"p:1",MeasurementStage.PROBE,0),probe=ProbeMeasurement(1,1,1.25)))
        val row=SpeedTestResult(ip,1,1,0.0,1.25,0.0,"N/A",probe=ProbeMeasurement(1,1,1.25))
        assertTrue(vm.persistCompletedRun(ledger.record(SpeedTestEngine.TestConfig(testCount=1,downloadTest=false),listOf(row),1,null)))
        val fresh=SpeedTestViewModel(app);idle();fresh.updateEnableDownloadTest(true)
        val run=fresh.runRecords.value.single()
        assertEquals(1,run.results.count { presentResult(it,run.config).bucket=="优选" })
        val source=File("src/main/java/com/cfst/app/ui/history/HistoryScreen.kt").readText()
        assertTrue("static wiring guard only: history must read frozen config",source.contains("val config=run?.config"))
    }
    @Test fun ownerObservationAndQuickRunRestrictionsAreExplicitlyWired() {
        // Static wiring guard only; rendering/interaction remains device-deferred.
        fun source(p:String)=File("src/main/java/com/cfst/app/$p").readText()
        for(p in listOf("ui/lists/IpListScreen.kt","ui/history/HistoryScreen.kt","ui/test/TestScreen.kt"))
            assertTrue("observed owner value must be read: $p",source(p).contains("operation.let"))
        val screen=source("ui/test/TestScreen.kt")
        assertTrue(screen.contains("vm.editableDraft.collectAsState()"))
        assertTrue(screen.contains("testTag(\"quick-save\")"))
        assertTrue(screen.contains("enabled=!running"))
        assertFalse("result grouping must not use live settings",screen.contains("downloadEnabled by vm.enableDownloadTest"))
    }
}
