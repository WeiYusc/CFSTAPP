package com.cfst.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.settings.*
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.model.*
import com.cfst.app.speedtest.*
import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class P01ClosureStateTest {
    private fun app()=ApplicationProvider.getApplicationContext<Application>()
    private fun idle()=Shadows.shadowOf(Looper.getMainLooper()).idle()
    @Test fun editorRejectsInvalidNextWithoutPublishingOrWriting() {
        val app=app(); val store=AppStateStore.get(app); val before=store.exportState(); val disk=File(app.filesDir,"app-state.json").readBytes()
        assertFalse("every authoritative editor validates next",store.edit().putBoolean("useSpecifiedIpSegments",true).commit())
        assertEquals(before,store.exportState()); assertArrayEquals(disk,File(app.filesDir,"app-state.json").readBytes())
        assertFalse(store.edit().putInt("testCount",0).commit()); assertFalse(store.edit().clear().commit())
        assertEquals(before,AppStateStore(app).exportState())
    }
    @Test fun realPreviewConfirmDisablesSelectionWhenReplacingSourceThenReopens() {
        val app=app(); val vm=SpeedTestViewModel(app); idle()
        vm.previewPaste("192.0.2.1"); assertTrue(vm.confirmImport()); vm.toggleSpecifiedIpSegment("192.0.2.1"); vm.updateUseSpecifiedIpSegments(true)
        vm.previewPaste("192.0.2.2"); assertTrue(vm.confirmImport())
        assertTrue(vm.selectedSpecifiedIpSegments.value.isEmpty()); assertFalse("empty selection disables same transaction",vm.useSpecifiedIpSegments.value)
        assertFalse(AppStateStore(app).getBoolean("useSpecifiedIpSegments",true))
        val reopened=SpeedTestViewModel(app); idle(); assertEquals("192.0.2.2",reopened.currentListContent.value); assertFalse(reopened.useSpecifiedIpSegments.value)
    }
    private fun record(failed:Boolean=false):RunRecord {
        val id=java.util.UUID.randomUUID().toString(); val ip="192.0.2.1"; val p=ProbeMeasurement(1,1,1.0)
        val d=DownloadMeasurement(bytes=65536,elapsedNanos=1000000000,peer=ip,status=200,error=if(failed) DownloadError.IO else DownloadError.NONE)
        val ledger=RunLedger(id,1,setOf(ip)); ledger.accept(MeasurementEvent(EventIdentity(id,ip,"p",MeasurementStage.PROBE,0),probe=p)); ledger.accept(MeasurementEvent(EventIdentity(id,ip,"d",MeasurementStage.DOWNLOAD,0),byteDelta=d.bytes)); ledger.accept(MeasurementEvent(EventIdentity(id,ip,"d",MeasurementStage.DOWNLOAD,1),download=d))
        val result=SpeedTestResult(ip,1,1,0.0,1.0,d.speed,"N/A",testTime=1,downloadedBytes=d.bytes,observedPeer=ip,downloadError=d.error.name,httpStatus=d.status,probe=p,download=d)
        return ledger.record(SpeedTestEngine.TestConfig(testCount=1),listOf(result),1,null)
    }
    @Test fun ambiguousLegacyRowsAndAllRawRunsRemainIndependentlyDeletable() {
        val app=app(); val store=AppStateStore.get(app); val a=record(); val b=record().copy(results=a.results); val gson=Gson()
        assertTrue(store.edit().putString("history_records",gson.toJson(listOf(a.results,a.results))).putString("run_records",gson.toJson(listOf(a,b))).commit())
        val vm=SpeedTestViewModel(app); idle()
        assertEquals("ambiguous legacy plus independent raw rows",4,vm.historyRecords.value.size)
        val label=vm.javaClass.methods.firstOrNull { it.name.startsWith("historyLabel") }; assertNotNull("UI uses owner identity labels",label)
        assertTrue((label!!.invoke(vm,0) as String).contains("归属未确定"))
        vm.deleteHistoryRecord(0); assertEquals(setOf(a.runId,b.runId),vm.runRecords.value.map { it.runId }.toSet())
        val ids=JsonParser.parseString(store.getString("history_run_ids","[]")).asJsonArray.map { it.asString }; vm.deleteHistoryRecord(ids.indexOf(a.runId)); assertEquals(b.runId,vm.runRecords.value.single().runId)
        val c=record(); assertTrue(vm.persistCompletedRun(c)); assertEquals(setOf(b.runId,c.runId),vm.runRecords.value.map { it.runId }.toSet())
        val payload=vm.exportAppState(); assertTrue(AppStateStore(app).restoreState(payload)); val fresh=SpeedTestViewModel(app); idle(); assertEquals(setOf(b.runId,c.runId),fresh.runRecords.value.map { it.runId }.toSet()); assertEquals(b.events,fresh.runRecords.value.first { it.runId==b.runId }.events)
        fresh.clearAllHistoryRecords(); val final=SpeedTestViewModel(app); idle(); assertTrue(final.historyRecords.value.isEmpty()); assertTrue(final.runRecords.value.isEmpty())
    }
    @Test fun duplicateLegacyRowsCannotClaimSingleRawRun() {
        val app=app(); val store=AppStateStore.get(app); val run=record(); val gson=Gson()
        assertTrue(store.edit().putString("history_records",gson.toJson(listOf(run.results,run.results))).putString("run_records",gson.toJson(listOf(run))).commit())
        val vm=SpeedTestViewModel(app); idle()
        assertEquals("both ambiguous rows plus independent raw run",3,vm.historyRecords.value.size)
        assertTrue(vm.historyLabel(0).contains("归属未确定")); assertTrue(vm.historyLabel(1).contains("归属未确定"))
        assertTrue(vm.historyLabel(2).contains(run.runId))
        val reopened=SpeedTestViewModel(app); idle()
        assertEquals(3,reopened.historyRecords.value.size)
        reopened.deleteHistoryRecord(0); reopened.deleteHistoryRecord(0)
        assertEquals(listOf(run.runId),reopened.runRecords.value.map { it.runId })
        assertEquals(run.events,reopened.runRecords.value.single().events)
        val payload=reopened.exportAppState(); assertTrue(AppStateStore(app).restoreState(payload))
        val restored=SpeedTestViewModel(app); idle()
        assertEquals(run.events,restored.runRecords.value.single().events)
        restored.deleteHistoryRecord(0)
        assertTrue(restored.runRecords.value.isEmpty()); assertTrue(restored.historyRecords.value.isEmpty())
    }
    private fun assertRejected(vm:SpeedTestViewModel,run:RunRecord) {
        val store=AppStateStore.get(app()); val before=store.exportState(); val disk=File(app().filesDir,"app-state.json").readBytes(); val rows=vm.historyRecords.value
        val root=JsonParser.parseString(before).asJsonObject; val fields=root.getAsJsonObject("fields")
        fun field(key:String,text:String) { fields.add(key,JsonObject().apply { addProperty("type","string");addProperty("value",text) }) }
        field("run_records",Gson().toJson(listOf(run))); field("history_records",Gson().toJson(listOf(run.results))); field("history_run_ids",Gson().toJson(listOf(run.runId)))
        assertFalse("contradictory ledger/result accepted",vm.restoreAppState(root.toString())); assertEquals(before,store.exportState()); assertArrayEquals(disk,File(app().filesDir,"app-state.json").readBytes()); assertEquals(rows,vm.historyRecords.value)
    }
    @Test fun restoreRejectsResultRecommendationContradictingRawFailureEvenWithMatchingHistory() {
        val vm=SpeedTestViewModel(app()); idle(); val bad=record(true); val good=record().results.single()
        assertRejected(vm,bad.copy(results=listOf(good)))
    }
    @Test fun restoreRejectsTerminalBytesContradictingConsumedAttemptTotal() {
        val vm=SpeedTestViewModel(app()); idle(); val r=record()
        val events=r.events.map { if(it.download!=null) it.copy(download=it.download!!.copy(bytes=65537)) else it }
        assertRejected(vm,r.copy(events=events))
    }
    @Test fun boundedReaderStopsAtCapPlusOneAndRejectsMalformedUtf8() {
        val reader=AppStateStore.Companion.javaClass.methods.firstOrNull { it.name=="readBoundedUtf8" }
        assertNotNull("state reads need streaming limit",reader)
        var consumed=0
        val input=object:InputStream() { override fun read():Int { consumed++; return 65 }; override fun read(b:ByteArray,off:Int,len:Int):Int { consumed+=len; b.fill(65,off,off+len); return len } }
        try { reader!!.invoke(AppStateStore.Companion,input,16); fail("oversize accepted") } catch(e:java.lang.reflect.InvocationTargetException) { assertTrue(e.cause is IllegalArgumentException) }
        assertEquals(17,consumed)
        try { reader!!.invoke(AppStateStore.Companion,ByteArrayInputStream(byteArrayOf(0xc3.toByte(),0x28)),16); fail("invalid UTF8 accepted") } catch(e:java.lang.reflect.InvocationTargetException) { assertTrue(e.cause is java.nio.charset.CharacterCodingException) }
    }
    @Test fun ambiguousMigrationUsesExplicitWholeRowRetentionWithoutDroppingRawAttempts() {
        val app=app(); val store=AppStateStore.get(app); val a=record(); val b=record().copy(results=a.results); val gson=Gson()
        assertTrue(store.edit().putString("history_records",gson.toJson(List(100) { a.results })).putString("run_records",gson.toJson(listOf(a,b))).commit())
        val vm=SpeedTestViewModel(app); idle(); assertEquals(100,vm.historyRecords.value.size); assertEquals(setOf(a.runId,b.runId),vm.runRecords.value.map { it.runId }.toSet())
        assertTrue(vm.statusMessage.value.contains("FIFO")); val payload=vm.exportAppState(); assertTrue(AppStateStore(app).restoreState(payload))
    }
    @Test fun restoreAcceptsOldChunkDeltasAndCurrentAggregateForSameTerminalBytes() {
        val vm=SpeedTestViewModel(app()); idle(); val r=record(); val terminal=r.events.last()
        val old=r.copy(events=r.events.dropLast(1)+listOf(terminal.copy(identity=terminal.identity.copy(sequence=1),download=null,byteDelta=32768),terminal.copy(identity=terminal.identity.copy(sequence=2),download=null,byteDelta=32768),terminal.copy(identity=terminal.identity.copy(sequence=3),byteDelta=0)))
        assertTrue(vm.persistCompletedRun(old)); assertTrue(AppStateStore(app()).restoreState(vm.exportAppState()))
        val mutated=r.copy(results=listOf(r.results.single().copy(packetLoss=1.0)))
        assertRejected(vm,mutated)
    }
    @Test fun actualOversizeAuthorityPreservesSourceForRecovery() {
        val app=app(); AppStateStore(app); val file=File(app.filesDir,"app-state.json")
        java.io.RandomAccessFile(file,"rw").use { it.setLength(AppStateStore.MAX_STATE_BYTES.toLong()+1) }
        try { AppStateStore(app); fail("oversize accepted") } catch(_:IllegalStateException) {}
        assertEquals(AppStateStore.MAX_STATE_BYTES.toLong()+1,file.length())
    }
}
