package com.cfst.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AndroidSmokeTest {
    @Test fun realOnStopCancelsPublishedRunToken() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val activity=controller.get()
        val vm=androidx.lifecycle.ViewModelProvider(activity, com.cfst.app.viewmodel.SpeedTestViewModel.createFactory(activity.application))[com.cfst.app.viewmodel.SpeedTestViewModel::class.java]
        val field=vm.javaClass.getDeclaredField("engine").apply { isAccessible=true }
        val engine=field.get(vm) as com.cfst.app.speedtest.SpeedTestEngine
        val token=engine.prepareRun()!!
        val testingField=vm.javaClass.getDeclaredField("_isTesting").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        val testing=testingField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
        testing.value=true
        controller.pause().stop()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(token.cancelled)
        assertEquals("离开前台，已停止",token.reason)
        controller.destroy()
    }
    @Test fun realComposeActivityCreatesAndResumes() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(controller.get().window.decorView)
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }
}
