package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.model.SpeedTestResult
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class HistoryTransactionTest {
    @Test fun failedHistorySaveDoesNotPublishUndurableHistory() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        @Suppress("UNCHECKED_CAST") val results=vm.javaClass.getDeclaredField("_results").apply {isAccessible=true}.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<SpeedTestResult>>
        results.value=listOf(SpeedTestResult("192.0.2.1",1,1,0.0,10.0,0.0,"N/A"))
        for(name in listOf("app-state.json","app-state.json.bak")) File(app.filesDir,name).apply { delete();mkdirs();resolve("block").writeText("block") }
        vm.saveCurrentResultsToHistory(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(vm.historyRecords.value.isEmpty()); assertTrue(vm.statusMessage.value.contains("失败"))
    }
    @Test fun historyCanExportItsOwnRecordRatherThanCurrentResults() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        @Suppress("UNCHECKED_CAST") val results=vm.javaClass.getDeclaredField("_results").apply {isAccessible=true}.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<SpeedTestResult>>
        results.value=listOf(SpeedTestResult("192.0.2.1",1,1,0.0,10.0,0.0,"N/A")); vm.saveCurrentResultsToHistory()
        results.value=emptyList()
        val method=vm.javaClass.methods.firstOrNull {it.name=="exportHistory"}; assertNotNull("history primary export must be wired",method)
        val csv=method!!.invoke(vm,0) as String; assertTrue(csv.contains("192.0.2.1")); assertFalse(vm.exportResults().contains("192.0.2.1"))
    }
}
