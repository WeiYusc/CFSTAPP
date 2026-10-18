package com.cfst.app

import android.app.Activity
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ImportLifecycleTest {
    private fun await(condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!condition() && System.nanoTime()<deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue(condition())
    }
    @Test fun actualLauncherReadsIntoPreviewWithoutApplying() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity=controller.get()
        val vm=ViewModelProvider(activity,SpeedTestViewModel.createFactory(activity.application))[SpeedTestViewModel::class.java]
        vm.loadIpRanges("192.0.2.1")
        val request=activity.javaClass.declaredMethods.firstOrNull { it.name.startsWith("requestListImport") }
        assertNotNull("stable Activity import launcher missing",request)
        request!!.isAccessible=true; request.invoke(activity)
        await { Shadows.shadowOf(activity).peekNextStartedActivityForResult()!=null }
        val launched=Shadows.shadowOf(activity).nextStartedActivityForResult
        val uri=android.net.Uri.parse("content://test/import-list")
        Shadows.shadowOf(activity.contentResolver).registerInputStream(uri,ByteArrayInputStream("192.0.2.2\n2001:db8::1".toByteArray()))
        activity.activityResultRegistry.dispatchResult(launched.requestCode,Activity.RESULT_OK,Intent().setData(uri))
        await { vm.importPreview.value!=null }
        assertEquals(listOf("192.0.2.1"),vm.availableIpSegments.value)
        vm.confirmImport()
        assertEquals(listOf("192.0.2.2","2001:db8::1"),vm.availableIpSegments.value)
        controller.pause().stop().destroy()
    }
}
