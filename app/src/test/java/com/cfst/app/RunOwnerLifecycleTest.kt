package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.speedtest.*
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RunOwnerLifecycleTest {
    @Test fun startCapturesConfigBeforeCoroutineEntry() {
        val vm=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.loadIpRanges("127.0.0.1"); vm.updateTestCount(1); vm.updateEnableDownloadTest(false)
        vm.startTest()
        val engine=vm.javaClass.getDeclaredField("engine").apply { isAccessible=true }.get(vm) as SpeedTestEngine
        assertNotNull("reserve must bind immutable settings",engine.runOwner!!.config)
        assertEquals(1,engine.runOwner!!.config!!.testCount)
        vm.updateTestCount(17)
        assertEquals(1,engine.runOwner!!.config!!.testCount)
        vm.stopTest()
    }
    @Test fun oldGuardEndCannotReleaseNewIdentity() {
        val guard=NetworkIdentityGuard {}
        guard.update("wifi:1")
        val begin=guard.javaClass.methods.firstOrNull { it.name=="begin" && it.parameterCount==1 }
        assertNotNull("guard owner identity not wired",begin)
        assertEquals(true,begin!!.invoke(guard,"run1"))
        val end=guard.javaClass.methods.first { it.name=="end" && it.parameterCount==1 }
        end.invoke(guard,"old")
        val captured=guard.javaClass.getDeclaredField("captured").apply { isAccessible=true }.get(guard)
        assertEquals("wifi:1",captured)
        end.invoke(guard,"run1")
        assertNull(guard.javaClass.getDeclaredField("captured").apply { isAccessible=true }.get(guard))
    }
}
