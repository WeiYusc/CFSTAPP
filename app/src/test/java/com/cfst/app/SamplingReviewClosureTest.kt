package com.cfst.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import com.cfst.app.settings.*
import com.cfst.app.speedtest.*
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class SamplingReviewClosureTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private fun idle()=Shadows.shadowOf(Looper.getMainLooper()).idle()
    private fun finish(vm:SpeedTestViewModel) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        do { idle();Thread.sleep(5) } while(vm.isTesting.value && System.nanoTime()<end)
        assertFalse(vm.statusMessage.value,vm.isTesting.value)
    }
    private class Clock:RunDeadlineClock {
        var fail=false;var tasks=0
        override fun nanoTime()=System.nanoTime()
        override fun schedule(delayNanos:Long,expire:()->Unit):RunDeadlineClock.DeadlineTask {
            if(fail) throw RejectedExecutionException("fixture schedule rejection")
            tasks++
            return object:RunDeadlineClock.DeadlineTask { override fun cancelAndJoin() { tasks-- } }
        }
    }
    private fun vm(clock:Clock):SpeedTestViewModel {
        val ctor=SpeedTestViewModel::class.java.declaredConstructors.firstOrNull { it.parameterCount==3 && it.parameterTypes[2]==RunDeadlineClock::class.java }
        assertNotNull("VM must permit existing deadline clock failure path verification",ctor)
        return ctor!!.apply { isAccessible=true }.newInstance(app,{ 42L },clock) as SpeedTestViewModel
    }
    @Test fun failedReserveReleasesOwnerAndSameEngineCanReserveAgain() {
        val clock=Clock();val engine=SpeedTestEngine(clock);val list=com.cfst.app.utils.IpRangeParser.parseIpFile("127.0.0.1")
        clock.fail=true
        assertTrue(runCatching { engine.reserve(SpeedTestEngine.TestConfig(),list) }.exceptionOrNull() is RejectedExecutionException)
        assertFalse("failed bind leaked running owner",engine.isRunning());assertEquals(RunOwner.State.RELEASED,engine.runOwner!!.state)
        assertEquals(0,clock.tasks);assertNull(engine.lastRecord)
        clock.fail=false;val token=engine.reserve(SpeedTestEngine.TestConfig(),list)!!
        assertNull(engine.reserve(SpeedTestEngine.TestConfig(),list));engine.releaseRun(token)
        assertFalse(engine.isRunning());assertEquals(0,clock.tasks)
        assertTrue(runCatching { engine.reserve(SpeedTestEngine.TestConfig(testCount=2),list,"parent","127.0.0.1") }.isFailure)
        assertFalse(engine.isRunning());assertEquals(0,clock.tasks)
    }
    @Test fun schedulerFailureShutsDownAllocatedExecutorBeforeRethrowing() {
        val timer=object:ScheduledThreadPoolExecutor(1) {
            override fun schedule(command:Runnable,delay:Long,unit:TimeUnit):ScheduledFuture<*> { throw RejectedExecutionException("fixture") }
        }
        try {
            val error=runCatching { MonotonicRunClock.scheduleOn(timer,1L) { Unit } }.exceptionOrNull()
            assertTrue("original scheduler exception must propagate: $error",error is RejectedExecutionException)
            assertTrue("allocated timer must shut down on failed scheduling",timer.isShutdown)
        } finally { timer.shutdownNow() }
    }
    @Test fun actualVmReserveFailurePreservesResultsAndHistoryThenRecovers() {
        val clock=Clock();val vm=vm(clock)
        assertTrue(vm.saveDraft(vm.beginDraft().copy(enableDownload=false,port="1",repetitions="1")));vm.loadIpRanges("127.0.0.1")
        vm.startTest();finish(vm);val results=vm.results.value;val history=vm.runRecords.value;val before=vm.exportAppState()
        clock.fail=true;vm.startTest()
        assertFalse(vm.isTesting.value);assertEquals(results,vm.results.value);assertEquals(history,vm.runRecords.value);assertEquals(before,vm.exportAppState())
        assertTrue(vm.statusMessage.value.contains("启动失败"));assertEquals(0,clock.tasks)
        clock.fail=false;vm.startTest();finish(vm);assertEquals(history.size+1,vm.runRecords.value.size);assertEquals(0,clock.tasks)
    }
    @Test fun actualViewModelStoreClearReleasesEnteredRun() {
        val clock=Clock();val vm=vm(clock);val owner=ViewModelStore();owner.put("fixture",vm)
        assertTrue(vm.saveDraft(vm.beginDraft().copy(enableDownload=false,port="1",repetitions="1")));vm.loadIpRanges("127.0.0.1")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        CandidateSampler.preparationObserver={ entered.countDown();check(release.await(5,TimeUnit.SECONDS)) }
        try {
            vm.startTest();idle();assertTrue("real worker did not enter preparation",entered.await(5,TimeUnit.SECONDS))
            val engine=vm.javaClass.getDeclaredField("engine").apply { isAccessible=true }.get(vm) as SpeedTestEngine
            assertTrue(engine.runOwner!!.entered)
            owner.clear();release.countDown();finish(vm);assertEquals(0,clock.tasks)
            assertFalse(engine.isRunning());assertEquals(RunOwner.State.RELEASED,engine.runOwner!!.state)
        } finally { release.countDown();CandidateSampler.preparationObserver=null }
    }
    @Test @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    fun explicitEngineReservationClearedVmCancelsThenCallerReleasesToken() {
        val clock=Clock();val vm=vm(clock);val holder=ViewModelStore();holder.put("fixture",vm)
        assertTrue(vm.saveDraft(vm.beginDraft().copy(enableDownload=false,port="1",repetitions="1")));vm.loadIpRanges("127.0.0.1")
        // This fixture reserves through the engine API; it is not a VM.startTest pre-entry proof.
        val engine=vm.javaClass.getDeclaredField("engine").apply { isAccessible=true }.get(vm) as SpeedTestEngine
        val token=engine.reserve(vm.savedTestConfig(),com.cfst.app.utils.IpRangeParser.parseIpFile("127.0.0.1"))!!
        assertFalse(engine.runOwner!!.entered);holder.clear()
        assertTrue(token.cancelled)
        engine.releaseRun(token)
        assertFalse(engine.isRunning());assertEquals(0,clock.tasks)
    }
    @Test fun actualNetworkCallbacksClassifyVpnWifiCellularAndUnknownWithoutBypassClaim() {
        val vm=SpeedTestViewModel(app)
        val callback=vm.javaClass.getDeclaredField("networkCallback").apply { isAccessible=true }.get(vm) as android.net.ConnectivityManager.NetworkCallback
        val network=org.robolectric.shadow.api.Shadow.newInstanceOf(android.net.Network::class.java)
        for((transport,label) in listOf(android.net.NetworkCapabilities.TRANSPORT_VPN to "VPN",android.net.NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",android.net.NetworkCapabilities.TRANSPORT_CELLULAR to "蜂窝")) {
            val caps=org.robolectric.shadow.api.Shadow.newInstanceOf(android.net.NetworkCapabilities::class.java)
            Shadows.shadowOf(caps).addTransportType(transport)
            callback.onCapabilitiesChanged(network,caps);assertTrue(vm.networkLabel.value,vm.networkLabel.value.endsWith(":"+label))
        }
        val caps=org.robolectric.shadow.api.Shadow.newInstanceOf(android.net.NetworkCapabilities::class.java)
        callback.onCapabilitiesChanged(network,caps);assertTrue(vm.networkLabel.value.endsWith(":其他"))
        callback.onLost(network);assertTrue(vm.networkLabel.value.startsWith("网络丢失"))
    }
    @Test fun validateAndRecoverPublicEntrypointsPreserveLegalFlagsAndRejectMalformedWithoutWrite() {
        val s=AppStateStore.get(app);val baseline=JsonParser.parseString(s.exportState()).asJsonObject
        val file=File(app.filesDir,"app-state.json")
        for(mode in listOf(false,true)) for(pending in listOf(false,true)) {
            val root=baseline.deepCopy();val fields=root["fields"].asJsonObject
            for((key,value) in mapOf("randomizeSampleSeed" to mode,"samplingMigrationNoticePending" to pending)) {
                val node=JsonObject();node.addProperty("type","boolean");node.addProperty("value",value);fields.add(key,node)
            }
            val before=file.readBytes();AppStateStore.validatePayload(app,root.toString());assertArrayEquals(before,file.readBytes())
            assertTrue(AppStateStore.recover(app,root.toString()))
            val fresh=AppStateStore(app)
            assertEquals(mode,fresh.getBoolean("randomizeSampleSeed",!mode));assertEquals(pending,fresh.getBoolean("samplingMigrationNoticePending",!pending))
        }
        for(key in listOf("randomizeSampleSeed","samplingMigrationNoticePending")) for(value in listOf(JsonNull.INSTANCE,JsonPrimitive("true"),JsonPrimitive(1))) {
            val root=baseline.deepCopy();val node=JsonObject()
            node.addProperty("type",if(value.isJsonNull) "boolean" else if(value.asJsonPrimitive.isString) "string" else "int");node.add("value",value);root["fields"].asJsonObject.add(key,node)
            file.writeText("preserved bad authority");val before=file.readBytes()
            val archives=File(app.filesDir,"state-recovery").listFiles()?.size ?: 0
            assertTrue(runCatching { AppStateStore.validatePayload(app,root.toString()) }.isFailure)
            assertTrue(runCatching { AppStateStore.recover(app,root.toString()) }.isFailure)
            assertArrayEquals(before,file.readBytes());assertEquals(archives,File(app.filesDir,"state-recovery").listFiles()?.size ?: 0)
        }
    }
    @Test fun editorRemovalClearAndWrongTypesLeaveAllStateAndBytesUnchanged() {
        val s=AppStateStore.get(app);val file=File(app.filesDir,"app-state.json")
        val before=s.exportState();val bytes=file.readBytes();val all=s.all
        for(key in listOf("randomizeSampleSeed","samplingMigrationNoticePending")) {
            assertFalse(s.edit().remove(key).commit());assertEquals(before,s.exportState());assertEquals(all,s.all);assertArrayEquals(bytes,file.readBytes())
            assertFalse(s.edit().putInt(key,1).commit());assertEquals(before,s.exportState());assertArrayEquals(bytes,file.readBytes())
        }
        assertFalse(s.edit().clear().commit());assertEquals(before,s.exportState());assertEquals(all,s.all);assertArrayEquals(bytes,file.readBytes())
    }
    @Test fun failedAcknowledgmentPreservesIndependentFlowSettingsHistoryAndDurableBytes() {
        val vm=SpeedTestViewModel(app);assertTrue(vm.restoreAppState(old().toString()))
        val state=vm.exportAppState();val history=vm.runRecords.value;val rows=vm.historyRecords.value;val config=vm.savedTestConfig()
        val pending=vm.samplingMigrationNoticePending.value;val mode=vm.randomizeSampleSeed.value
        val dir=app.filesDir;val bytes=File(dir,"app-state.json").readBytes();val held=File(dir.parentFile,dir.name+"-ack-held")
        assertTrue(dir.renameTo(held));assertTrue(dir.createNewFile())
        try {
            assertFalse(vm.acknowledgeSamplingMigration());assertEquals(pending,vm.samplingMigrationNoticePending.value)
            assertEquals(mode,vm.randomizeSampleSeed.value);assertEquals(config,vm.savedTestConfig());assertEquals(history,vm.runRecords.value);assertEquals(rows,vm.historyRecords.value)
            assertEquals(state,AppStateStore.get(app).exportState());assertArrayEquals(bytes,File(held,"app-state.json").readBytes())
        } finally { assertTrue(dir.delete());assertTrue(held.renameTo(dir)) }
        assertEquals(state,vm.exportAppState());assertArrayEquals(bytes,File(dir,"app-state.json").readBytes())
    }
    @Test fun confirmedRetestReserveFailureAndBackgroundRejectionPreserveParentThenRecover() {
        val clock=Clock();var calls=0;val vm=SpeedTestViewModel(app,{calls++;42L},clock)
        assertTrue(vm.saveDraft(vm.beginDraft().copy(enableDownload=false,port="1",repetitions="1")));vm.loadIpRanges("127.0.0.1")
        vm.startTest();finish(vm);val parent=vm.runRecords.value.first();val before=vm.exportAppState();val rows=vm.results.value;val bytes=File(app.filesDir,"app-state.json").readBytes()
        var accepted=true;val worker=Thread { accepted=vm.confirmSingleIpRetest(parent.runId,"127.0.0.1") }
        val count=calls;worker.start();worker.join(2000);assertFalse(worker.isAlive);assertFalse(accepted);assertEquals(count,calls)
        clock.fail=true;assertFalse(vm.confirmSingleIpRetest(parent.runId,"127.0.0.1"))
        assertFalse(vm.isTesting.value);assertEquals(rows,vm.results.value);assertEquals(listOf(parent),vm.runRecords.value)
        assertEquals(before,vm.exportAppState());assertArrayEquals(bytes,File(app.filesDir,"app-state.json").readBytes());assertEquals(0,clock.tasks)
        clock.fail=false;assertTrue(vm.confirmSingleIpRetest(parent.runId,"127.0.0.1"));finish(vm)
        assertEquals(parent,vm.runRecords.value.last());assertEquals(parent.runId,vm.runRecords.value.first().parentRunId);assertEquals(0,clock.tasks)
    }
    private fun old():JsonObject=JsonParser.parseString(AppStateStore.get(app).exportState()).asJsonObject.also {
        it["fields"].asJsonObject.remove("randomizeSampleSeed");it["fields"].asJsonObject.remove("samplingMigrationNoticePending")
    }
    @Test fun legacyAndRecoveryPublicEntrypointsRetainPendingAndFixedSeed() {
        val root=old();val authority=File(app.filesDir,"app-state.json");assertTrue(authority.delete())
        app.getSharedPreferences("local_settings",0).edit().putLong("sampleSeed",77L).commit()
        val legacy=AppStateStore(app)
        assertTrue(legacy.getBoolean("randomizeSampleSeed",false));assertTrue(legacy.getBoolean("samplingMigrationNoticePending",false));assertEquals(77L,legacy.getLong("sampleSeed",0))
        authority.writeText("broken-authority")
        assertTrue(AppStateStore.recover(app,root.toString()));val recovered=AppStateStore.get(app)
        assertTrue(recovered.getBoolean("randomizeSampleSeed",false));assertTrue(recovered.getBoolean("samplingMigrationNoticePending",false))
        assertTrue(File(app.filesDir,"state-recovery").walkTopDown().any { it.isFile && it.readText()=="broken-authority" })
    }
    @Test fun authoritativeCorruptFlagsNeverFallbackAndEditorRejectsWrongTypes() {
        val s=AppStateStore.get(app);val file=File(app.filesDir,"app-state.json");val original=file.readBytes()
        for(key in listOf("randomizeSampleSeed","samplingMigrationNoticePending")) {
            assertFalse(s.edit().putString(key,"true").commit());assertArrayEquals(original,file.readBytes())
            for(value in listOf(JsonNull.INSTANCE,JsonPrimitive("true"),JsonPrimitive(1))) {
                val root=JsonParser.parseString(String(original)).asJsonObject
                val f=JsonObject();f.addProperty("type","boolean");f.add("value",value);root["fields"].asJsonObject.add(key,f)
                file.writeText(root.toString());val bad=file.readBytes()
                assertTrue(runCatching { AppStateStore(app) }.isFailure);assertArrayEquals(bad,file.readBytes())
                file.writeBytes(original)
            }
        }
    }
    @Test fun allLegalModeAndNoticeValuesSurviveRestoreReopenAndEditorCommit() {
        val s=AppStateStore.get(app)
        for(mode in listOf(false,true)) for(pending in listOf(false,true)) {
            assertTrue(s.edit().putBoolean("randomizeSampleSeed",mode).putBoolean("samplingMigrationNoticePending",pending).commit())
            val backup=s.exportState();assertTrue(s.restoreState(backup));val reopened=AppStateStore(app)
            assertEquals(mode,reopened.getBoolean("randomizeSampleSeed",!mode));assertEquals(pending,reopened.getBoolean("samplingMigrationNoticePending",!pending))
        }
    }
}
