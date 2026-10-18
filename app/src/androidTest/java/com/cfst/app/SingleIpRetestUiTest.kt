package com.cfst.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.activity.compose.setContent
import com.cfst.app.model.*
import com.cfst.app.speedtest.*
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compiled device acceptance only; requires a real device to claim touch/UI PASS. */
class SingleIpRetestUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun historyDetailCancelThenExplicitConfirmCreatesOneIndependentRealSocketRun() {
        val vm=ViewModelProvider(compose.activity,SpeedTestViewModel.createFactory(compose.activity.application))[SpeedTestViewModel::class.java]
        val id=java.util.UUID.randomUUID().toString()
        val ledger=RunLedger(id,1,setOf("127.0.0.1"))
        val probe=ProbeMeasurement(1,1,1.0)
        ledger.accept(MeasurementEvent(EventIdentity(id,"127.0.0.1","probe:127.0.0.1",MeasurementStage.PROBE,1),probe=probe))
        val parent=ledger.record(SpeedTestEngine.TestConfig(testCount=1,downloadTest=false),listOf(SpeedTestResult("127.0.0.1",1,1,0.0,1.0,0.0,"N/A",probe=probe)),1,null)
        java.net.ServerSocket(0).use { socket ->
            compose.runOnIdle {
                assertTrue(vm.persistCompletedRun(parent))
                assertTrue(vm.saveDraft(vm.beginDraft().copy(enableDownload=false,repetitions="1",port=socket.localPort.toString())))
            }
            compose.activityRule.scenario.onActivity { activity -> activity.setContent {
                androidx.compose.material3.MaterialTheme { com.cfst.app.ui.history.HistoryScreen(vm,{},{},{},{}) }
            } }
            compose.onNodeWithText("查看记录详情").performScrollTo().performClick()
            compose.onNodeWithTag("detail-127.0.0.1").performScrollTo().performClick()
            compose.onNodeWithTag("retest-127.0.0.1").performScrollTo().performClick()
            compose.onNodeWithText("确认单 IP 复测").assertIsDisplayed()
            compose.onNodeWithText("取消复测").performClick()
            compose.runOnIdle { assertFalse(vm.isTesting.value);assertEquals(listOf(parent),vm.runRecords.value) }
            compose.onNodeWithTag("retest-127.0.0.1").performScrollTo().performClick()
            compose.onNodeWithText("确认并开始新运行").performClick()
            compose.waitUntil(10000) { !vm.isTesting.value && vm.runRecords.value.size==2 }
            compose.runOnIdle {
                val child=vm.runRecords.value.first()
                assertEquals(id,child.parentRunId);assertNotEquals(id,child.runId)
                assertEquals(1,child.summary.probePassed);assertEquals(1,child.config!!.testCount)
                assertEquals(parent,vm.runRecords.value.last())
            }
        }
    }
}
