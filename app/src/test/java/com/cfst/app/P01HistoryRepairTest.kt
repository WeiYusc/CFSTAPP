package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.model.*
import com.cfst.app.settings.*
import com.cfst.app.speedtest.*
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class P01HistoryRepairTest {
    private fun record(count:Int=1):RunRecord {
        val id=java.util.UUID.randomUUID().toString()
        val ips=(1..count).map { "10.${(it shr 16) and 255}.${(it shr 8) and 255}.${it and 255}" }
        val ledger=RunLedger(id,count,ips.toSet())
        ips.forEach { ledger.accept(MeasurementEvent(EventIdentity(id,it,"p:$it",MeasurementStage.PROBE,0),probe=ProbeMeasurement(1,1,1.0))) }
        return ledger.record(SpeedTestEngine.TestConfig(testCount=count),ips.map { SpeedTestResult(it,1,1,0.0,1.0,0.0,"N/A") },1,null)
    }
    private fun save(vm:SpeedTestViewModel,r:RunRecord) {
        val method=vm.javaClass.declaredMethods.firstOrNull { it.name.startsWith("persistCompletedRun") }
        assertNotNull("completed run must have one durable owner transaction",method)
        method!!.isAccessible=true; assertEquals(true,method.invoke(vm,r))
    }
    @Test fun deleteByHistoryIdentityRemovesRawRunEvenWithLegacyIndexOffset() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        @Suppress("UNCHECKED_CAST") val results=vm.javaClass.getDeclaredField("_results").apply { isAccessible=true }.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<SpeedTestResult>>
        val old=SpeedTestResult("192.0.2.1",1,1,0.0,1.0,0.0,"legacy")
        results.value=listOf(old); vm.saveCurrentResultsToHistory()
        val first=record(); save(vm,first); val second=record(); save(vm,second)
        vm.deleteHistoryRecord(1)
        assertEquals(listOf(second.runId),vm.runRecords.value.map { it.runId })
        assertFalse(vm.exportAppState().contains(first.runId))
        vm.clearAllHistoryRecords()
        val reopened=SpeedTestViewModel(app); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(reopened.runRecords.value.isEmpty()); assertTrue(reopened.historyRecords.value.isEmpty())
        assertFalse(reopened.exportAppState().contains(second.runId))
    }
    @Test fun preRepairHistoryWithoutIdsMapsRawRunIdentityRatherThanListOffset() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val store=AppStateStore.get(app)
        val first=record(); val second=record(2)
        val legacy=listOf(SpeedTestResult("192.0.2.99",1,1,0.0,1.0,0.0,"legacy"))
        val gson=com.google.gson.Gson()
        assertTrue(store.edit().putString("history_records",gson.toJson(listOf(legacy,second.results,first.results))).putString("run_records",gson.toJson(listOf(first,second))).commit())
        val vm=SpeedTestViewModel(app); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        vm.deleteHistoryRecord(1)
        assertEquals(listOf(first.runId),vm.runRecords.value.map { it.runId })
    }
    @Test fun largestSupportedRunReopensAndRoundTripsWithinByteRetention() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        save(vm,record(10000))
        val payload=vm.exportAppState()
        assertTrue(payload.toByteArray().size>2097152)
        val store=AppStateStore(app)
        assertTrue(store.restoreState(payload))
        val reopened=SpeedTestViewModel(app); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(10000,reopened.runRecords.value.single().summary.generatedUnique)
        assertEquals(payload,reopened.exportAppState())
        repeat(101) { save(vm,record()) }
        assertEquals(100,vm.runRecords.value.size); assertEquals(100,vm.historyRecords.value.size)
        assertTrue(vm.exportAppState().toByteArray().size<=32*1024*1024)
    }
    @Test fun restoreRejectsSwappedHistoryRunIdMappingBeforeCommit() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        val first=record(); val second=record(2); save(vm,first); save(vm,second)
        val before=vm.exportAppState(); val root=com.google.gson.JsonParser.parseString(before).asJsonObject
        root["fields"].asJsonObject["history_run_ids"].asJsonObject.addProperty("value",com.google.gson.Gson().toJson(listOf(first.runId,second.runId)))
        assertFalse(vm.restoreAppState(root.toString())); assertEquals(before,vm.exportAppState())
    }
    @Test fun multipleLargeRunsRetainWholeAttemptsWithinExplicitByteBudget() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        repeat(5) { save(vm,record(10000)) }
        assertTrue("byte retention must drop old whole runs before count cap",vm.runRecords.value.size<5)
        assertTrue(vm.runRecords.value.all { it.events.size==10000 && it.results.size==10000 })
        assertEquals(vm.runRecords.value.size,vm.historyRecords.value.size)
        val state=AppStateStore(app); assertTrue(state.restoreState(vm.exportAppState()))
    }
    @Test fun real300MiBBodySaveReopenExportRestoreConservesAttemptLedger() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        val id=java.util.UUID.randomUUID().toString(); val ledger=RunLedger(id,1,setOf("127.0.0.1"))
        val identity=EventIdentity(id,"127.0.0.1","download:0",MeasurementStage.DOWNLOAD,0)
        ledger.accept(MeasurementEvent(identity)); var sequence=0L
        val bytes=300*1048576L
        java.net.ServerSocket(0).use { server ->
            val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
            val sent=worker.submit {
                server.accept().use { socket ->
                    val reader=socket.getInputStream().bufferedReader(); while(!reader.readLine().isNullOrEmpty()) {}
                    val out=socket.getOutputStream(); out.write("HTTP/1.1 200 OK\r\nContent-Length: $bytes\r\nConnection: close\r\n\r\n".toByteArray())
                    val chunk=ByteArray(8192)
                    repeat(38400) { out.write(chunk) }; out.flush()
                }
            }
            try {
                val measured=DownloadTransport().measure("127.0.0.1","http://body.test/",server.localPort,60000,ByteBudget(bytes),RunCancellation(),true) { delta ->
                    ledger.accept(MeasurementEvent(identity.copy(sequence=++sequence),byteDelta=delta))
                }
                assertEquals(bytes,measured.bytes); assertTrue(measured.transportValid)
                ledger.accept(MeasurementEvent(identity.copy(sequence=++sequence),download=measured))
                val r=ledger.record(SpeedTestEngine.TestConfig(testCount=1,byteBudget=bytes),emptyList(),measured.elapsedNanos,null)
                save(vm,r); val payload=vm.exportAppState(); val store=AppStateStore(app)
                assertTrue(store.restoreState(payload)); val fresh=SpeedTestViewModel(app)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(bytes,fresh.runRecords.value.single().summary.bodyBytesConsumed)
                assertEquals(bytes,fresh.runRecords.value.single().events.sumOf { it.byteDelta })
                assertEquals(payload,fresh.exportAppState()); assertTrue(payload.toByteArray().size<8192)
                sent.get(5,java.util.concurrent.TimeUnit.SECONDS)
            } finally { worker.shutdownNow() }
        }
    }
    @Test fun failedDeleteNeverPublishesEitherIndex() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        val run=record(); save(vm,run)
        for(name in listOf("app-state.json","app-state.json.bak")) File(app.filesDir,name).apply { delete();mkdirs();resolve("block").writeText("block") }
        vm.clearAllHistoryRecords()
        assertEquals(run.runId,vm.runRecords.value.single().runId); assertEquals(1,vm.historyRecords.value.size)
    }
}
