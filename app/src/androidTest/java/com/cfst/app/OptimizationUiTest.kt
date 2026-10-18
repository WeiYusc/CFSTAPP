package com.cfst.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import androidx.lifecycle.ViewModelProvider
import com.cfst.app.viewmodel.SpeedTestViewModel
import androidx.test.platform.app.InstrumentationRegistry
import android.app.Instrumentation
import android.content.IntentFilter
import android.content.Intent
import android.app.Activity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.*
import java.util.concurrent.*

class OptimizationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun pastePreviewApplyFeedbackAndTabs() {
        compose.onNodeWithTag("nav-列表").performClick()
        compose.onNodeWithText("粘贴列表").performClick()
        compose.onNodeWithTag("paste-input").performTextReplacement("192.0.2.1\n2001:db8::1")
        compose.onNodeWithText("粘贴预览").performScrollTo().performClick()
        compose.onNodeWithText("确认应用").performScrollTo().performClick()
        compose.onNodeWithText("已加载 2 个 IP 段",substring=true).assertExists()
        compose.onNodeWithTag("nav-测速").performClick()
        compose.onNodeWithTag("bucket-短样本").performScrollTo().performClick()
        compose.onNodeWithTag("bucket-异常/未达标").performClick()
        compose.onNodeWithTag("nav-历史").performClick()
        compose.onNodeWithText("旧记录/配置未知",substring=true).assertExists()
    }
    @Test fun emptyImportHasCurrentPageFeedback() {
        compose.onNodeWithTag("nav-列表").performClick()
        compose.onNodeWithText("粘贴列表").performClick()
        compose.onNodeWithTag("paste-input").performTextReplacement("# comment")
        compose.onNodeWithText("粘贴预览").performScrollTo().performClick()
        compose.onNodeWithText("empty",substring=true).assertExists()
    }
    @Test fun cancelDraftDoesNotPersist() {
        compose.onNodeWithTag("nav-设置").performClick()
        compose.onNodeWithTag("candidates-input").performTextReplacement("99")
        compose.onNodeWithText("取消草稿").performClick()
        compose.onNodeWithTag("nav-设置").performClick()
        compose.onNodeWithTag("candidates-input").assertTextContains("30")
    }
    @Test fun tabsDoNotRemoveRunningForegroundStopAndSocketOwner() {
        val connected=CountDownLatch(1); val release=CountDownLatch(1)
        var socket:java.net.Socket?=null
        java.net.ServerSocket(0).use { server ->
            val vm=ViewModelProvider(compose.activity,SpeedTestViewModel.createFactory(compose.activity.application))[SpeedTestViewModel::class.java]
            com.cfst.app.speedtest.TcpPingTest.connectionObserver={ s -> socket=s; connected.countDown(); release.await(10,TimeUnit.SECONDS) }
            try {
                compose.runOnUiThread {
                    vm.loadIpRanges("127.0.0.1"); vm.updateDownloadPort(server.localPort)
                    vm.updatePingCount(1); vm.updateEnableDownloadTest(false)
                    vm.startTest(); assertTrue("must actually be running before navigation",vm.isTesting.value)
                }
                assertTrue("real socket entered",connected.await(5,TimeUnit.SECONDS))
                compose.onNodeWithTag("nav-列表").performClick()
                compose.runOnIdle { assertTrue(vm.isTesting.value) }
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                assertTrue("ON_STOP closes real registered socket",socket!!.isClosed)
                release.countDown()
                compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                compose.waitUntil(5000) { !vm.isTesting.value }
                compose.onNodeWithTag("nav-列表").assertIsSelected()
            } finally { release.countDown(); com.cfst.app.speedtest.TcpPingTest.connectionObserver=null }
        }
    }
    @Test fun largeFontAndNarrowConfigurationRemainScrollable() {
        val vm=ViewModelProvider(compose.activity,SpeedTestViewModel.createFactory(compose.activity.application))[SpeedTestViewModel::class.java]
        val density=compose.activity.resources.displayMetrics.density
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density,1.8f)) {
                androidx.compose.material3.MaterialTheme {
                    Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("constrained-root")) {
                        com.cfst.app.ui.settings.AdvancedSettingsScreen(vm) {}
                    }
                }
            }
        } }
        val root=compose.onNodeWithTag("constrained-root").fetchSemanticsNode().boundsInRoot
        assertEquals(320*density,root.width,1f)
        compose.onNodeWithText("资源 展开").performScrollTo().performClick()
        compose.onNodeWithText("安全端点 展开").performScrollTo().performClick()
        compose.onNodeWithText("目标端口（覆盖URL端口）").performScrollTo()
        val input=compose.onNodeWithText("目标端口（覆盖URL端口）").fetchSemanticsNode().boundsInRoot
        assertTrue(input.left>=root.left-1 && input.right<=root.right+1)
        compose.onNodeWithText("取消草稿").assertIsDisplayed()
    }
    @Test fun actualFileButtonLauncherResultReadsProviderThenPreviewApply() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val uri=android.net.Uri.parse("content://com.cfst.app.local.test.p01.fixture/list")
        val monitor=instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT),Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)),true)
        try {
            compose.onNodeWithTag("nav-列表").performClick()
            compose.onNodeWithText("文件导入").performClick()
            val vm=ViewModelProvider(compose.activity,SpeedTestViewModel.createFactory(compose.activity.application))[SpeedTestViewModel::class.java]
            compose.waitUntil(5000) { vm.importPreview.value!=null }
            assertEquals(1,monitor.hits)
            compose.onNodeWithText("确认应用").performScrollTo().performClick()
            compose.runOnIdle { assertEquals("192.0.2.17\n2001:db8::17",vm.currentListContent.value) }
            assertEquals(com.cfst.app.documents.DocumentOperationOwner.Stage.DONE,com.cfst.app.documents.DocumentOperationOwner.get(compose.activity.application).state.value!!.stage)
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun quickButtonsDraftConfirmSaveThenFreezeActualRunConfiguration() {
        val vm=ViewModelProvider(compose.activity,SpeedTestViewModel.createFactory(compose.activity.application))[SpeedTestViewModel::class.java]
        val before=com.cfst.app.settings.AppStateStore.get(compose.activity.application).exportState()
        compose.onNodeWithText("省流",useUnmergedTree=true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(before,com.cfst.app.settings.AppStateStore.get(compose.activity.application).exportState()) }
        compose.onNodeWithTag("quick-candidates").performTextReplacement("12")
        compose.onNodeWithTag("quick-budget").performScrollTo().performTextReplacement("4")
        compose.onNodeWithText("确认快捷设置").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(12,vm.testCount.value); assertEquals(4000000L,vm.budgetBytes.value) }
        compose.runOnUiThread {
            vm.loadIpRanges("127.0.0.1"); vm.startTest()
            val engine=vm.javaClass.getDeclaredField("engine").apply { isAccessible=true }.get(vm) as com.cfst.app.speedtest.SpeedTestEngine
            assertEquals(12,engine.runOwner!!.config!!.testCount)
            assertEquals(4000000L,engine.runOwner!!.config!!.byteBudget)
            vm.stopTest()
        }
    }
}
