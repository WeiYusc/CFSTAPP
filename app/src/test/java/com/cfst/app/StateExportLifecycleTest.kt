package com.cfst.app

import android.app.Activity
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.documents.DocumentOperationOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class StateExportLifecycleTest {
    private fun await(condition:()->Boolean) { val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); while(!condition() && System.nanoTime()<deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }; assertTrue(condition()) }
    @Test fun actualStateExportLauncherWritesVersionedFullState() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity=controller.get()
        val vm=ViewModelProvider(activity,SpeedTestViewModel.createFactory(activity.application))[SpeedTestViewModel::class.java]
        vm.loadIpRanges("192.0.2.1")
        val request=activity.javaClass.declaredMethods.firstOrNull { it.name.startsWith("requestStateExport") }
        assertNotNull("state export launcher not wired",request); request!!.isAccessible=true; request.invoke(activity)
        await { Shadows.shadowOf(activity).peekNextStartedActivityForResult()!=null }
        val launched=Shadows.shadowOf(activity).nextStartedActivityForResult
        val uri=android.net.Uri.parse("content://test/full-state"); val output=ByteArrayOutputStream()
        Shadows.shadowOf(activity.contentResolver).registerOutputStream(uri,output)
        activity.activityResultRegistry.dispatchResult(launched.requestCode,Activity.RESULT_OK,Intent().setData(uri))
        val owner=DocumentOperationOwner.get(activity.application)
        await { owner.state.value?.stage==DocumentOperationOwner.Stage.DONE }
        assertTrue(output.toString("UTF-8").contains("schemaVersion")); assertTrue(output.toString("UTF-8").contains("192.0.2.1"))
        controller.pause().stop().destroy()
    }
    @Test fun csvExportAndFileImportShareSingleDocumentSlot() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity=controller.get()
        val method=activity.javaClass.declaredMethods.first { it.name.startsWith("requestCsvExport") }
        method.isAccessible=true; method.invoke(activity)
        await { Shadows.shadowOf(activity).peekNextStartedActivityForResult()!=null }
        assertNull("CSV picker must own same SAF slot",DocumentOperationOwner.get(activity.application).picker(0))
        controller.pause().stop().destroy()
    }
}
