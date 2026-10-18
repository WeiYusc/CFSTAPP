package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.settings.*
import com.cfst.app.speedtest.*
import com.cfst.app.model.RunLedger
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class N3SettingsTest {
    private fun store()=AppStateStore.get(ApplicationProvider.getApplicationContext<Application>())
    @Test fun authoritativeSettingsHaveSafeLegacyDefaults() {
        val fields=JsonParser.parseString(store().exportState()).asJsonObject["fields"].asJsonObject
        assertTrue("deadline absent from authority",fields.has("runTimeSeconds"))
        assertEquals(120,fields["runTimeSeconds"].asJsonObject["value"].asInt)
        assertTrue(fields.has("sampleSeed"))
    }
    @Test fun oldRunConfigMissingFieldsNormalizesBeforeGsonUnsafe() {
        val record=RunLedger(java.util.UUID.randomUUID().toString(),1,emptySet()).record(SpeedTestEngine.TestConfig(testCount=1),emptyList(),1,null)
        val node=Gson().toJsonTree(record).asJsonObject
        node["config"].asJsonObject.remove("runTimeSeconds"); node["config"].asJsonObject.remove("sampleSeed")
        val restored=runCatching { SnapshotRecords.runs("[$node]").single() }.getOrNull()
        assertNotNull("legacy missing fields must normalize",restored)
        assertEquals(120,restored!!.config!!.runTimeSeconds); assertEquals(0L,restored.config!!.sampleSeed)
    }
    @Test fun maliciousNullAndInvalidNumericConfigRejectWithoutWrite() {
        val s=store(); val before=s.exportState()
        for(value in listOf(JsonNull.INSTANCE,JsonPrimitive(29),JsonPrimitive(601),JsonPrimitive(30.5))) {
            val root=JsonParser.parseString(before).asJsonObject
            val field=JsonObject(); field.addProperty("type","int");field.add("value",value);root["fields"].asJsonObject.add("runTimeSeconds",field)
            val accepted=runCatching { s.restoreState(root.toString()) }.getOrDefault(false)
            assertFalse("invalid deadline accepted: $value",accepted);assertEquals(before,s.exportState())
        }
    }
    @Test fun draftDeadlineAndSeedPersistAcrossActualVmAndBackupRestore() {
        val vm=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        val json=Gson().toJsonTree(vm.beginDraft()).asJsonObject
        assertTrue("draft deadline absent",json.has("runTimeSeconds"));assertTrue(json.has("sampleSeed"))
        json.addProperty("runTimeSeconds","45");json.addProperty("sampleSeed","-9223372036854775808")
        assertTrue(vm.saveDraft(Gson().fromJson(json,SettingsDraft::class.java)))
        val second=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        val saved=Gson().toJsonTree(second.beginDraft()).asJsonObject
        assertEquals("45",saved["runTimeSeconds"].asString);assertEquals("-9223372036854775808",saved["sampleSeed"].asString)
        assertTrue(second.restoreAppState(vm.exportAppState()))
        assertEquals("45",Gson().toJsonTree(second.beginDraft()).asJsonObject["runTimeSeconds"].asString)
    }
    @Test fun actualVmReservesFrozenSavedDeadlineSeedAndNeverReadsDirtyDraft() {
        var calls=0
        val vm=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>()) { calls++;5678L }
        assertTrue(vm.saveDraft(vm.beginDraft().copy(runTimeSeconds="37",sampleSeed="1234",randomizeSampleSeed=false)))
        vm.loadIpRanges("127.0.0.1")
        vm.startTest()
        val engine=vm.javaClass.getDeclaredField("engine").apply { isAccessible=true }.get(vm) as SpeedTestEngine
        assertEquals(37,engine.runOwner!!.config!!.runTimeSeconds)
        assertEquals(5678L,engine.runOwner!!.config!!.sampleSeed);assertEquals(1,calls)
        // Stop only after a raw result exists. Stop-before-entry may legitimately produce
        // no historical row; that separate cancellation path cannot prove saved seed replay.
        val resultDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while(vm.results.value.isEmpty() && vm.isTesting.value && System.nanoTime()<resultDeadline) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10) }
        assertTrue("raw result required before stopped-history assertion",vm.results.value.isNotEmpty())
        vm.stopTest();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while(vm.isTesting.value && System.nanoTime()<deadline) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10) }
        assertFalse(vm.isTesting.value);assertEquals(RunOwner.State.RELEASED,engine.runOwner!!.state)
        assertEquals(37,SnapshotRecords.runs(AppStateStore.get(ApplicationProvider.getApplicationContext()).getString("run_records","[]")!!).last().config!!.runTimeSeconds)
    }
    @Test fun invalidSaveRetainsDeadlineSeedDraftAndInvalidRunEnumsReject() {
        val vm=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        val original=vm.beginDraft()
        for(draft in listOf(original.copy(runTimeSeconds="29"),original.copy(sampleSeed="9223372036854775808"))) {
            assertFalse(vm.saveDraft(draft)); assertEquals(draft,vm.beginDraft()); assertTrue(vm.draftPending.value)
        }
        val record=RunLedger(java.util.UUID.randomUUID().toString(),1,emptySet()).record(SpeedTestEngine.TestConfig(testCount=1),emptyList(),1,null)
        for(field in listOf("runTimeSeconds","sampleSeed")) {
            val node=Gson().toJsonTree(record).asJsonObject
            node["config"].asJsonObject.add(field,JsonNull.INSTANCE)
            assertTrue(runCatching { SnapshotRecords.runs("[$node]") }.isFailure)
        }
    }
    @Test fun legacyBackupMissingNewKeysIsAcceptedButExplicitNullSeedRejected() {
        val s=store(); val root=JsonParser.parseString(s.exportState()).asJsonObject
        root["fields"].asJsonObject.remove("sampleSeed");root["fields"].asJsonObject.remove("runTimeSeconds")
        assertTrue(s.restoreState(root.toString()))
        assertEquals(120,s.getInt("runTimeSeconds",-1));assertEquals(0L,s.getLong("sampleSeed",-1))
        val node=JsonObject();node.addProperty("type","long");node.add("value",JsonNull.INSTANCE);root["fields"].asJsonObject.add("sampleSeed",node)
        assertFalse(runCatching { s.restoreState(root.toString()) }.getOrDefault(false))
    }
}
