package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.model.*
import com.cfst.app.speedtest.*
import com.cfst.app.ui.presentation.*
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class TcpOnlyEligibilityRepairTest {
    private fun row(delay:Double?=25.0,received:Int=1,cancelled:Boolean=false,d:DownloadMeasurement?=null):SpeedTestResult {
        val p=ProbeMeasurement(3,received,delay,cancelled)
        return SpeedTestResult("127.0.0.1",3,received,p.loss ?: 0.0,delay ?: 0.0,d?.speed ?: 0.0,"N/A",downloadedBytes=d?.bytes ?: 0,probe=p,download=d)
    }
    private fun present(r:SpeedTestResult,c:SpeedTestEngine.TestConfig?):ResultPresentation {
        val method=Class.forName("com.cfst.app.ui.presentation.ResultPresentationKt").methods.firstOrNull { it.name=="presentResult" && it.parameterTypes.size==2 && it.parameterTypes[1]==SpeedTestEngine.TestConfig::class.java }
        assertNotNull("presentation must consume frozen full run config",method)
        return method!!.invoke(null,r,c) as ResultPresentation
    }
    @Test fun tcpThresholdMatrixUsesKnownRunConfigNotConnectionSuccessAlone() {
        val c=SpeedTestEngine.TestConfig(downloadTest=false,latencyLimit=25.0)
        for(delay in listOf(0.1,24.9,25.0)) assertEquals("优选",present(row(delay),c).bucket)
        assertEquals("异常/未达标",present(row(25.1),c).bucket)
        assertTrue(present(row(25.1),c).status.contains("延迟筛选未达标"))
        assertEquals("优选",present(row(999.0),c.copy(latencyLimit=0.0)).bucket)
        assertEquals("优选",present(row(),c.copy(downloadTest=true,pingOnly=true)).bucket)
        for(r in listOf(row(null),row(null,0),row(1.0,1,true))) assertNotEquals("优选",present(r,c).bucket)
        assertNotEquals("优选",present(row(),null).bucket)
        assertNotEquals("优选",present(row(),c.copy(downloadTest=true)).bucket)
    }
    @Test fun everyExistingDownloadAlwaysKeepsDownloadQualityPriority() {
        val qualified=DownloadMeasurement(65536,1000000000,"127.0.0.1",200,DownloadError.BUDGET,DownloadTermination.BYTE_LIMIT,true)
        val short=qualified.copy(bytes=16)
        val failed=qualified.copy(error=DownloadError.IO,termination=DownloadTermination.IO_FAILURE)
        val cancelled=qualified.copy(error=DownloadError.CANCELLED,termination=DownloadTermination.USER_STOP)
        for(c in listOf(SpeedTestEngine.TestConfig(downloadTest=false),SpeedTestEngine.TestConfig(pingOnly=true))) {
            assertEquals("优选",present(row(d=qualified),c).bucket)
            assertEquals("短样本",present(row(d=short),c).bucket)
            for(d in listOf(failed,cancelled)) assertEquals("异常/未达标",present(row(d=d),c).bucket)
        }
    }
    @Test fun downloadPriorityEightCasesSurviveLegalLedgerStoreReopenAndRestore() {
        val app=ApplicationProvider.getApplicationContext<Application>();val vm=SpeedTestViewModel(app)
        val qualified=DownloadMeasurement(65536,1000000000,"127.0.0.1",200,DownloadError.BUDGET,DownloadTermination.BYTE_LIMIT,true)
        val matrix=listOf("QUALIFIED" to qualified,"SHORT" to qualified.copy(bytes=16),"FAILED" to qualified.copy(error=DownloadError.IO,termination=DownloadTermination.IO_FAILURE),"CANCELLED" to qualified.copy(error=DownloadError.CANCELLED,termination=DownloadTermination.USER_STOP))
        val expected=mutableMapOf<String,String>()
        for(config in listOf(SpeedTestEngine.TestConfig(testCount=1,downloadTest=false),SpeedTestEngine.TestConfig(testCount=1,pingOnly=true))) for((name,d) in matrix) {
            val r=row(d=d).copy(observedPeer=d.peer,httpStatus=d.status,downloadError=d.error.name)
            val id=java.util.UUID.randomUUID().toString();val ledger=RunLedger(id,1,setOf(r.ipAddress))
            assertTrue(ledger.accept(MeasurementEvent(EventIdentity(id,r.ipAddress,"probe",MeasurementStage.PROBE,1),probe=r.probe)))
            assertTrue(ledger.accept(MeasurementEvent(EventIdentity(id,r.ipAddress,"download",MeasurementStage.DOWNLOAD,1),download=d,byteDelta=d.bytes)))
            val run=ledger.record(config,listOf(r),1000000000,null)
            assertTrue("legal durable matrix rejected: ${config.downloadTest}/${config.pingOnly}/$name",vm.persistCompletedRun(run))
            expected[id]=when(name) { "QUALIFIED" -> "优选";"SHORT" -> "短样本";else -> "异常/未达标" }
        }
        val store=com.cfst.app.settings.AppStateStore(app)
        val stored=com.cfst.app.settings.SnapshotRecords.runs(store.getString("run_records","[]")!!)
        assertEquals(8,stored.size)
        val fresh=SpeedTestViewModel(app);assertTrue(fresh.restoreAppState(store.exportState()))
        assertEquals(8,fresh.runRecords.value.size)
        for(run in fresh.runRecords.value) {
            assertEquals(expected.getValue(run.runId),present(run.results.single(),run.config).bucket)
            assertEquals(run,stored.single { it.runId==run.runId })
            assertTrue(fresh.exportHistoryIdentity(run.runId).contains(run.results.single().download!!.termination.name))
        }
    }
    @Test fun realSocketCurrentResultKeepsFrozenThresholdAfterSavedSettingsChange() {
        val app=ApplicationProvider.getApplicationContext<Application>();val vm=SpeedTestViewModel(app)
        java.net.ServerSocket(0,8,java.net.InetAddress.getByName("::1")).use { server ->
            assertTrue(vm.saveDraft(vm.beginDraft().copy(port=server.localPort.toString(),enableDownload=false,repetitions="1",latency="0")))
            server.soTimeout=5000
            vm.updateUseIpv6(true);vm.loadIpRanges("::1");vm.startTest()
            val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            do { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(5) } while(vm.isTesting.value && System.nanoTime()<end)
            assertFalse(vm.statusMessage.value,vm.isTesting.value);server.accept().close()
            val r=vm.results.value.single();assertTrue(r.probe!!.passed);assertNotNull(vm.getBestIp())
            val delay=r.probe!!.delayMs!!;assertTrue(delay>0)
            vm.updateLatencyLimit(delay/2);assertEquals(r,vm.getBestIp())
            assertEquals(0.0,vm.runRecords.value.first().config!!.latencyLimit,0.0)
            assertEquals(vm.runRecords.value,SpeedTestViewModel(app).runRecords.value)
        }
    }
    @Test fun realLedgerDurableHistoryReopenRestoreAndCsvKeepEachRunsThreshold() {
        val app=ApplicationProvider.getApplicationContext<Application>();val vm=SpeedTestViewModel(app)
        val r=row();val configs=listOf(SpeedTestEngine.TestConfig(testCount=1,downloadTest=false,latencyLimit=20.0),SpeedTestEngine.TestConfig(testCount=1,downloadTest=false,latencyLimit=30.0))
        configs.forEach { config ->
            val id=java.util.UUID.randomUUID().toString();val ledger=RunLedger(id,1,setOf(r.ipAddress))
            ledger.accept(MeasurementEvent(EventIdentity(id,r.ipAddress,"probe:1",MeasurementStage.PROBE,1),probe=r.probe))
            assertTrue(vm.persistCompletedRun(ledger.record(config,listOf(r),1,null)))
        }
        vm.updateLatencyLimit(1.0)
        val export=vm.exportAppState();val fresh=SpeedTestViewModel(app);assertTrue(fresh.restoreAppState(export))
        assertEquals(2,fresh.runRecords.value.size)
        fresh.runRecords.value.forEach { run ->
            assertEquals(if(run.config!!.latencyLimit==30.0) "优选" else "异常/未达标",present(run.results.single(),run.config).bucket)
            assertEquals(0,run.summary.recommended);assertTrue(fresh.exportHistoryIdentity(run.runId).contains(r.ipAddress))
        }
    }
}
