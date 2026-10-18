package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.settings.AppStateStore
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.model.*
import com.cfst.app.speedtest.*
import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class P01StateRepairTest {
    private fun app()=ApplicationProvider.getApplicationContext<Application>()
    @Test fun atomicInterruptedWriteRecoversOriginalCompleteSnapshot() {
        val app=app(); val store=AppStateStore(app)
        assertTrue(store.edit().putInt("testCount",47).commit()); val original=store.exportState()
        val atomic=android.util.AtomicFile(File(app.filesDir,"app-state.json"))
        val interrupted=atomic.startWrite(); interrupted.write("{partial".toByteArray()); interrupted.fd.sync(); interrupted.close()
        assertEquals(original,AppStateStore(app).exportState())
    }
    @Test fun corruptAuthoritativeFileNeverMigratesOrOverwritesLegacy() {
        val app=app(); val store=AppStateStore(app)
        store.edit().putInt("testCount",47).commit()
        val file=File(app.filesDir,"app-state.json"); file.writeText("{broken")
        app.getSharedPreferences("local_settings",0).edit().putInt("testCount",99).commit()
        try { AppStateStore(app); fail("corrupt authority was silently replaced") } catch(_:IllegalStateException) {}
        assertEquals("{broken",file.readText())
    }
    @Test fun oldGsonHistoryFieldsBecomeSafeUnknownForVmCsvExport() {
        val app=app()
        val old="""[{"ipAddress":"192.0.2.1","packetsSent":1,"packetsReceived":1,"packetLoss":0.0,"avgLatency":1.0,"downloadSpeed":2.0,"regionCode":"N/A","testTime":1}]"""
        app.getSharedPreferences("app_prefs",0).edit().putString("history_records",Gson().toJson(listOf(old))).commit()
        val vm=SpeedTestViewModel(app); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val csv=vm.exportHistory(0)
        assertTrue(csv.contains("2.10")); assertTrue(csv.contains("UNKNOWN"))
        assertEquals("UNKNOWN",vm.historyRecords.value[0][0].downloadError)
    }
    @Test fun validShapeButMissingNestedFieldAndNullLegacyFieldAreRejected() {
        val store=AppStateStore(app()); val before=store.exportState()
        val r=Gson().toJsonTree(validRun()).asJsonObject
        r["config"].asJsonObject.remove("pingCount")
        val root=JsonParser.parseString(before).asJsonObject
        root["fields"].asJsonObject.add("run_records",JsonObject().apply {addProperty("type","string");addProperty("value","[$r]")})
        try { store.restoreState(root.toString()); fail("missing mandatory nested field accepted") } catch(_:IllegalArgumentException) {}
        assertEquals(before,store.exportState())
        val old="""[[{"ipAddress":"192.0.2.1","packetsSent":1,"packetsReceived":1,"packetLoss":0,"avgLatency":1,"downloadSpeed":0,"regionCode":null}]]"""
        root["fields"].asJsonObject.remove("run_records")
        root["fields"].asJsonObject.add("history_records",JsonObject().apply {addProperty("type","string");addProperty("value",old)})
        try { store.restoreState(root.toString()); fail("malicious legacy null accepted") } catch(_:IllegalArgumentException) {}
        assertEquals(before,store.exportState())
    }
    @Test fun validIpSelectionOutsideSourceCannotCommitThenMutateDuringVmPublish() {
        val app=app(); val vm=SpeedTestViewModel(app); vm.loadIpRanges("192.0.2.1")
        val store=AppStateStore.get(app); val before=store.exportState(); val root=JsonParser.parseString(before).asJsonObject
        root["fields"].asJsonObject["selectedSegments"].asJsonObject.add("value",JsonParser.parseString("[\"192.0.2.99\"]"))
        assertFalse(vm.restoreAppState(root.toString())); assertEquals(before,store.exportState())
    }
    @Test fun typedSnapshotRejectsSelectedWrongFamilyAndDuplicateRunIds() {
        val app=app(); val vm=SpeedTestViewModel(app); vm.loadIpRanges("192.0.2.1\n2001:db8::1")
        val store=AppStateStore.get(app); val before=store.exportState()
        val root=JsonParser.parseString(before).asJsonObject
        root["fields"].asJsonObject["selectedSegments"].asJsonObject.add("value",JsonParser.parseString("[\"2001:db8::1\"]"))
        assertFalse(vm.restoreAppState(root.toString())); assertEquals(before,store.exportState())
        val r=validRun(); root["fields"].asJsonObject["selectedSegments"].asJsonObject.add("value",JsonArray())
        root["fields"].asJsonObject.add("run_records",JsonObject().apply { addProperty("type","string");addProperty("value",Gson().toJson(listOf(r,r))) })
        assertFalse(vm.restoreAppState(root.toString())); assertEquals(before,store.exportState())
    }
    @Test fun fractionalSchemaAndWrongOptionalStateTypesAreRejectedPrecommit() {
        val store=AppStateStore(app()); val before=store.exportState()
        val roots=listOf(
            JsonParser.parseString(before).asJsonObject.apply { addProperty("schemaVersion",2.5) },
            JsonParser.parseString(before).asJsonObject.apply { getAsJsonObject("fields").add("sourceGeneration",JsonParser.parseString("""{"type":"string","value":"oops"}""")) }
        )
        roots.forEach { root ->
            try { store.restoreState(root.toString()); fail("invalid typed state accepted") } catch(_:IllegalArgumentException) {}
            assertEquals(before,store.exportState())
        }
    }
    private fun validRun():RunRecord {
        val id=java.util.UUID.randomUUID().toString(); val ledger=RunLedger(id,1,setOf("192.0.2.1"))
        ledger.accept(MeasurementEvent(EventIdentity(id,"192.0.2.1","p",MeasurementStage.PROBE,0),probe=ProbeMeasurement(1,1,1.0)))
        return ledger.record(SpeedTestEngine.TestConfig(testCount=1),listOf(SpeedTestResult("192.0.2.1",1,1,0.0,1.0,0.0,"N/A")),1,null)
    }
    @Test fun nestedInvalidRestoreCannotChangeDiskStoreOrVm() {
        val app=app(); val vm=SpeedTestViewModel(app); val store=AppStateStore.get(app)
        val run=validRun()
        assertTrue(store.edit().putString("run_records",Gson().toJson(listOf(run))).commit())
        val before=store.exportState(); val disk=File(app.filesDir,"app-state.json").readBytes()
        val mutations=listOf<(JsonObject)->Unit>(
            { it["config"].asJsonObject.addProperty("testCount",1.5) },
            { it["summary"].asJsonObject.addProperty("probePassed",4294967297L) },
            { it["summary"].asJsonObject.addProperty("probePassed",2) },
            { it["events"].asJsonArray[0].asJsonObject["identity"].asJsonObject.addProperty("stage","BOGUS") },
            { it["events"].asJsonArray[0].asJsonObject.addProperty("byteDelta",-1) },
            { it["results"].asJsonArray[0].asJsonObject.add("observedPeer",JsonNull.INSTANCE) },
            { it["results"].asJsonArray[0].asJsonObject.addProperty("probe","bad nested string") },
            { it["config"].asJsonObject.addProperty("downloadTest","false") },
            { it["events"].asJsonArray[0].asJsonObject["identity"].asJsonObject.addProperty("sequence",-1) },
            { it["summary"].asJsonObject.addProperty("recommended",1) },
            { it["results"].asJsonArray[0].asJsonObject.addProperty("avgLatency","1") }
            ,{ it.addProperty("runId",java.util.UUID.randomUUID().toString()) }
            ,{ it["summary"].asJsonObject.addProperty("probePassed",0); it["summary"].asJsonObject.addProperty("probeFailed",1) }
            ,{ it["summary"].asJsonObject.addProperty("uniqueDownloadCandidates",1) }
            ,{ it["summary"].asJsonObject.addProperty("probePassedNotDownloaded",0) }
            ,{ it["events"].asJsonArray.add(it["events"].asJsonArray[0].deepCopy().asJsonObject.apply { getAsJsonObject("identity").addProperty("sequence",2) }) }
        )
        mutations.forEachIndexed { n,mutate ->
            val root=JsonParser.parseString(before).asJsonObject
            val runs=JsonParser.parseString(root["fields"].asJsonObject["run_records"].asJsonObject["value"].asString).asJsonArray
            mutate(runs[0].asJsonObject)
            root["fields"].asJsonObject["run_records"].asJsonObject.addProperty("value",runs.toString())
            assertFalse("mutation $n accepted",vm.restoreAppState(root.toString()))
            assertEquals(before,store.exportState()); assertArrayEquals(disk,File(app.filesDir,"app-state.json").readBytes()); assertEquals(30,vm.testCount.value)
        }
    }
}
