package com.cfst.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.model.SpeedTestResult
import com.cfst.app.speedtest.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class UiRunStateTest {
    private fun vm()=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>()).also { Shadows.shadowOf(Looper.getMainLooper()).idle() }
    @Suppress("UNCHECKED_CAST") private fun results(vm:SpeedTestViewModel,row:SpeedTestResult) {
        (vm.javaClass.getDeclaredField("_results").apply { isAccessible=true }.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<SpeedTestResult>>).value=listOf(row)
    }
    @Test fun unknownLegacyProbeCannotBecomeVerifiedBestLatency() {
        val vm=vm(); vm.updateEnableDownloadTest(false)
        results(vm,SpeedTestResult("192.0.2.1",1,1,0.0,1.0,0.0,"legacy"))
        assertNull("unknown legacy quality must not headline a verified best",vm.getBestIp())
    }
    @Test fun reservationPublishesPreparingWithoutOldProgressBeforeCoroutineEntry() {
        val vm=vm(); vm.loadIpRanges("127.0.0.1")
        @Suppress("UNCHECKED_CAST") val progress=vm.javaClass.getDeclaredField("_progress").apply { isAccessible=true }.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<SpeedTestEngine.TestProgress>
        progress.value=SpeedTestEngine.TestProgress(99,100,"192.0.2.99","串行下载测速")
        vm.startTest()
        assertTrue(vm.isTesting.value)
        assertEquals("准备候选",vm.progress.value.status); assertEquals(0,vm.progress.value.total)
        vm.stopTest(); Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun stopPublishesAuthoritativeFinishingLatchUntilOwnerRelease() {
        val vm=vm(); vm.loadIpRanges("127.0.0.1"); vm.startTest(); vm.stopTest()
        val getter=vm.javaClass.methods.firstOrNull { it.name in setOf("getIsStopping","isStopping") }
        assertNotNull("stop state must survive page recreation",getter)
        val flow=getter!!.invoke(vm) as kotlinx.coroutines.flow.StateFlow<*>
        assertEquals(true,flow.value); assertTrue(vm.isTesting.value)
        val engine=vm.javaClass.getDeclaredField("engine").apply { isAccessible=true }.get(vm) as SpeedTestEngine
        assertEquals(RunOwner.State.STOPPING,engine.runOwner!!.state)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while(vm.isTesting.value && System.nanoTime()<deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) }
        assertFalse(vm.isTesting.value); assertEquals(false,flow.value);assertEquals(RunOwner.State.RELEASED,engine.runOwner!!.state)
    }
}
