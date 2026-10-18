package com.cfst.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import com.cfst.app.model.SpeedTestResult
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CsvLifecycleTest {
    private fun request(activity: MainActivity) {
        val method = MainActivity::class.java.declaredMethods.firstOrNull { it.name.startsWith("requestCsvExport") }
        assertNotNull("Activity must own durable pending export before launching picker", method)
        method!!.isAccessible = true
        method.invoke(activity)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("asynchronous export completed", condition())
    }
    @Test fun cancelledPickerCleansRealOwnersSnapshotAfterRecreation() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        request(first.get())
        await { Shadows.shadowOf(first.get()).peekNextStartedActivityForResult() != null }
        val launched = Shadows.shadowOf(first.get()).nextStartedActivityForResult
        val state = Bundle()
        first.pause().saveInstanceState(state).stop().destroy()
        val id = state.getString("pendingCsvId")!!
        val second = Robolectric.buildActivity(MainActivity::class.java).create(state).start().restoreInstanceState(state).resume()
        val file = java.io.File(second.get().filesDir, "pending-csv/$id.csv")
        assertTrue(file.exists())
        second.get().activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_CANCELED, null)
        assertFalse(file.exists())
        second.pause().stop().destroy()
    }

    @Test fun missingRealOwnersSnapshotReportsFailureWithoutWriting() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        request(first.get())
        await { Shadows.shadowOf(first.get()).peekNextStartedActivityForResult() != null }
        val launched = Shadows.shadowOf(first.get()).nextStartedActivityForResult
        val state = Bundle()
        first.pause().saveInstanceState(state).stop().destroy()
        val second = Robolectric.buildActivity(MainActivity::class.java).create(state).start().restoreInstanceState(state).resume()
        assertTrue(java.io.File(second.get().filesDir, "pending-csv/${state.getString("pendingCsvId")}.csv").delete())
        val uri = android.net.Uri.parse("content://test/missing-owner")
        val output = ByteArrayOutputStream()
        Shadows.shadowOf(second.get().contentResolver).registerOutputStream(uri, output)
        second.get().activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        await { org.robolectric.shadows.ShadowToast.getTextOfLatestToast()?.startsWith("保存失败：") == true }
        assertEquals(0, output.size())
        second.pause().stop().destroy()
    }

    @Test fun pendingOwnerSurvivesNewActivityAndDeliversFrozenCsv() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity = first.get()
        val vm = ViewModelProvider(activity, SpeedTestViewModel.createFactory(activity.application))[SpeedTestViewModel::class.java]
        val field = vm.javaClass.getDeclaredField("_results").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val results = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<SpeedTestResult>>
        results.value = listOf(SpeedTestResult("192.0.2.17", 3, 3, 0.0, 7.0, 2.5, "original"))
        val expected = vm.exportResults()
        request(activity)
        await { Shadows.shadowOf(activity).peekNextStartedActivityForResult() != null }
        val launched = Shadows.shadowOf(activity).nextStartedActivityForResult
        results.value = emptyList()
        val state = Bundle()
        first.pause().saveInstanceState(state).stop().destroy()
        // New owner/ViewModel, not controller.recreate's retained ViewModel.
        val second = Robolectric.buildActivity(MainActivity::class.java).create(state).start().restoreInstanceState(state).resume().visible()
        val uri = android.net.Uri.parse("content://test/lifecycle")
        val output = ByteArrayOutputStream()
        Shadows.shadowOf(second.get().contentResolver).registerOutputStream(uri, output)
        second.get().activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        await { org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == "CSV 已保存" }
        assertEquals(expected, output.toString("UTF-8"))
        assertTrue(java.io.File(second.get().filesDir, "pending-csv").listFiles().orEmpty().isEmpty())
        second.pause().stop().destroy()
    }
}
