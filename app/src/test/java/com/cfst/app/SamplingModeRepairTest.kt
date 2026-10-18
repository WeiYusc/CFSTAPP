package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.settings.*
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class SamplingModeRepairTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private fun store()=AppStateStore.get(app)
    private fun old():JsonObject=JsonParser.parseString(store().exportState()).asJsonObject.also {
        it["fields"].asJsonObject.remove("randomizeSampleSeed");it["fields"].asJsonObject.remove("samplingMigrationNoticePending")
    }
    private fun vmWithSeed(source:()->Long):SpeedTestViewModel {
        val ctor=SpeedTestViewModel::class.java.declaredConstructors.firstOrNull { it.parameterCount==2 && it.parameterTypes[1].name=="kotlin.jvm.functions.Function0" }
        assertNotNull("bounded per-run seed constructor missing",ctor)
        return ctor!!.apply { isAccessible=true }.newInstance(app,source) as SpeedTestViewModel
    }
    private fun finish(vm:SpeedTestViewModel) {
        val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        do { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(5) } while(vm.isTesting.value && System.nanoTime()<end)
        assertFalse(vm.statusMessage.value,vm.isTesting.value)
    }
    @Test fun explorationResolvesOncePerAcceptedRunAndFreezesEffectiveSeed() {
        var calls=0;val vm=vmWithSeed { (++calls).toLong()*65536 }
        vm.savedTestConfig();vm.beginDraft();vm.cancelDraft();assertEquals(0,calls)
        java.net.ServerSocket(0,16,java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            assertTrue(vm.saveDraft(vm.beginDraft().copy(port=server.localPort.toString(),enableDownload=false,repetitions="1",candidates="1")))
            vm.loadIpRanges("127.0.0.1")
            repeat(2) { index ->
                vm.startTest();vm.startTest();assertEquals(index+1,calls);finish(vm)
                assertEquals((index+1L)*65536,vm.runRecords.value.first().config!!.sampleSeed)
                server.accept().close()
            }
        }
        val records=vm.runRecords.value;assertEquals(records,SpeedTestViewModel(app).runRecords.value)
        assertTrue(vm.restoreAppState(vm.exportAppState()));assertEquals(records,vm.runRecords.value)
    }
    @Test fun blockedAndFixedRunsNeverConsumeExplorationSource() {
        var calls=0;val vm=vmWithSeed { calls++;error("must not resolve") }
        vm.updateDraft(vm.beginDraft().copy(candidates="2"));vm.startTest();assertEquals(0,calls);vm.cancelDraft()
        val tree=Gson().toJsonTree(vm.beginDraft()).asJsonObject;tree.addProperty("randomizeSampleSeed",false);tree.addProperty("sampleSeed",Long.MIN_VALUE.toString());tree.addProperty("enableDownload",false);tree.addProperty("port","1");tree.addProperty("repetitions","1")
        assertTrue(vm.saveDraft(Gson().fromJson(tree,SettingsDraft::class.java)));vm.loadIpRanges("127.0.0.1")
        repeat(2) { vm.startTest();finish(vm);assertEquals(Long.MIN_VALUE,vm.runRecords.value.first().config!!.sampleSeed) }
        assertEquals(0,calls)
    }
    @Test fun seedFailurePreservesPreviousResultsHistoryAndDoesNotReserve() {
        var fail=false;val vm=vmWithSeed { if(fail) error("fixture seed failure") else 17L }
        assertTrue(vm.saveDraft(vm.beginDraft().copy(enableDownload=false,port="1",repetitions="1")));vm.loadIpRanges("127.0.0.1")
        vm.startTest();finish(vm);val results=vm.results.value;val history=vm.runRecords.value;val bytes=File(app.filesDir,"app-state.json").readBytes()
        fail=true;vm.startTest();assertFalse(vm.isTesting.value);assertEquals(results,vm.results.value);assertEquals(history,vm.runRecords.value)
        assertArrayEquals(bytes,File(app.filesDir,"app-state.json").readBytes());assertTrue(vm.statusMessage.value.contains("启动失败"))
        fail=false;vm.startTest();finish(vm);assertEquals(history.size+1,vm.runRecords.value.size)
    }
    @Test fun acknowledgmentIsExplicitAtomicAndSurvivesRecreation() {
        val vm=SpeedTestViewModel(app);assertTrue(vm.restoreAppState(old().toString()))
        val method=vm.javaClass.methods.firstOrNull { it.name=="acknowledgeSamplingMigration" }
        assertNotNull("explicit migration acknowledgment missing",method)
        val directory=app.filesDir;val held=File(directory.parentFile,directory.name+"-sampling-held")
        assertTrue(directory.renameTo(held));assertTrue(directory.createNewFile())
        try { assertEquals(false,method!!.invoke(vm));assertTrue(store().getBoolean("samplingMigrationNoticePending",false)) }
        finally { assertTrue(directory.delete());assertTrue(held.renameTo(directory)) }
        assertEquals(true,method!!.invoke(vm));assertFalse(AppStateStore(app).getBoolean("samplingMigrationNoticePending",true))
        assertTrue(vm.restoreAppState(old().toString()));assertTrue(store().getBoolean("samplingMigrationNoticePending",false))
    }
    @Test fun backgroundStartIsRejectedWithoutSeedConsumption() {
        var calls=0;val vm=vmWithSeed { calls++;11L };vm.loadIpRanges("127.0.0.1")
        val worker=Thread { vm.startTest() };worker.start();worker.join(2000)
        assertFalse(worker.isAlive);assertEquals(0,calls);assertFalse(vm.isTesting.value)
    }
    @Test fun noticeSurvivesRunAndOnlySuccessfulModeSaveAcknowledges() {
        val vm=SpeedTestViewModel(app);assertTrue(vm.restoreAppState(old().toString()))
        assertTrue(vm.samplingMigrationNoticePending.value)
        assertTrue(vm.saveDraft(vm.beginDraft().copy(enableDownload=false,port="1",repetitions="1")))
        vm.loadIpRanges("127.0.0.1");vm.startTest();finish(vm)
        assertTrue(vm.samplingMigrationNoticePending.value)
        assertTrue(vm.saveDraft(vm.beginDraft().copy(randomizeSampleSeed=false)))
        assertFalse(vm.samplingMigrationNoticePending.value);assertFalse(vm.randomizeSampleSeed.value)
        assertFalse(SpeedTestViewModel(app).randomizeSampleSeed.value)
        assertEquals(false,vm.beginDraft().randomizeSampleSeed)
    }
    @Test fun newInstallationHasExplorationWithoutMigrationNotice() {
        assertTrue(store().getBoolean("randomizeSampleSeed",false))
        assertTrue(store().contains("samplingMigrationNoticePending"))
        assertFalse(store().getBoolean("samplingMigrationNoticePending",true))
    }
    @Test fun oldBackupNormalizesBothFlagsAndPreservesFixedSeed() {
        val s=store();val root=old();root["fields"].asJsonObject["sampleSeed"].asJsonObject.addProperty("value",Long.MIN_VALUE)
        assertTrue(s.restoreState(root.toString()))
        assertTrue(s.getBoolean("randomizeSampleSeed",false));assertTrue(s.getBoolean("samplingMigrationNoticePending",false))
        assertEquals(Long.MIN_VALUE,s.getLong("sampleSeed",0))
        assertEquals(s.exportState(),AppStateStore(app).exportState())
    }
    @Test fun oldAuthorityReopenAndValidationDoNotConsumeOrWriteNotice() {
        val root=old();val file=File(app.filesDir,"app-state.json");file.writeText(root.toString());val before=file.readBytes()
        AppStateStore.validatePayload(app,root.toString())
        val reopened=AppStateStore(app)
        assertTrue(reopened.getBoolean("samplingMigrationNoticePending",false))
        assertArrayEquals(before,file.readBytes())
        assertTrue(reopened.edit().putInt("testCount",31).commit())
        assertTrue(AppStateStore(app).getBoolean("samplingMigrationNoticePending",false))
    }
    @Test fun existingModeMissingNoticeDoesNotInventMigration() {
        val root=old();val field=JsonObject();field.addProperty("type","boolean");field.addProperty("value",false)
        root["fields"].asJsonObject.add("randomizeSampleSeed",field)
        assertTrue(store().restoreState(root.toString()));assertFalse(store().getBoolean("randomizeSampleSeed",true))
        assertTrue(store().contains("samplingMigrationNoticePending"));assertFalse(store().getBoolean("samplingMigrationNoticePending",true))
    }
    @Test fun malformedTypedModeFlagsRejectWithoutDurableMutation() {
        val s=store();val before=s.exportState();val bytes=File(app.filesDir,"app-state.json").readBytes()
        for(key in listOf("randomizeSampleSeed","samplingMigrationNoticePending")) for(value in listOf(JsonNull.INSTANCE,JsonPrimitive("true"),JsonPrimitive(1))) {
            val root=JsonParser.parseString(before).asJsonObject;val field=JsonObject()
            field.addProperty("type",if(value.isJsonNull) "boolean" else if(value.asJsonPrimitive.isString) "string" else "int");field.add("value",value)
            root["fields"].asJsonObject.add(key,field)
            assertFalse("accepted malformed $key=$value",runCatching { s.restoreState(root.toString()) }.getOrDefault(false))
            assertEquals(before,s.exportState());assertArrayEquals(bytes,File(app.filesDir,"app-state.json").readBytes())
        }
    }
    @Test fun missingModeCannotSuppressNoticeWithExplicitFalse() {
        val root=old();val field=JsonObject();field.addProperty("type","boolean");field.addProperty("value",false)
        root["fields"].asJsonObject.add("samplingMigrationNoticePending",field)
        assertTrue(store().restoreState(root.toString()));assertTrue(store().getBoolean("samplingMigrationNoticePending",false))
    }
    @Test fun draftExposesPersistedModeAndCancelDoesNotConsumeNotice() {
        val vm=SpeedTestViewModel(app);assertTrue(vm.restoreAppState(old().toString()))
        val draft=Gson().toJsonTree(vm.beginDraft()).asJsonObject
        assertTrue("mode absent from real draft",draft.has("randomizeSampleSeed"));assertTrue(draft["randomizeSampleSeed"].asBoolean)
        vm.cancelDraft();vm.reportDocumentStatus("unrelated status")
        assertTrue(store().getBoolean("samplingMigrationNoticePending",false))
    }
}
