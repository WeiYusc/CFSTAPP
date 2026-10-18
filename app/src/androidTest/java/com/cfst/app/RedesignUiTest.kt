package com.cfst.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import com.cfst.app.viewmodel.SpeedTestViewModel
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.*

/** Device-only acceptance; never counted as JVM/owner PASS. */
class RedesignUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun vm()=ViewModelProvider(compose.activity,SpeedTestViewModel.createFactory(compose.activity.application))[SpeedTestViewModel::class.java]
    @Test fun untouchedSettingsBackDoesNotDirtyAndSelectedNavigationIsExplicit() {
        compose.onNodeWithTag("nav-测速").assertIsSelected()
        compose.onNodeWithTag("nav-设置").performClick(); compose.onNodeWithTag("nav-设置").assertIsSelected()
        compose.runOnIdle { assertFalse(vm().draftPending.value) }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("nav-测速").assertIsSelected(); compose.onNodeWithTag("primary-run-action").assertIsEnabled()
    }
    @Test fun dirtyLeaveStayDiscardAndSaveRetainActualInputs() {
        compose.onNodeWithTag("nav-设置").performClick(); compose.onNodeWithTag("candidates-input").performTextReplacement("71")
        compose.onNodeWithTag("nav-列表").performClick(); compose.onNodeWithText("配置尚未保存").assertIsDisplayed()
        compose.onNodeWithText("留在编辑").performClick(); compose.onNodeWithTag("candidates-input").assertTextContains("71")
        compose.onNodeWithTag("nav-测速").performClick(); compose.onNodeWithText("保存并离开").performClick()
        compose.runOnIdle { assertEquals(71,vm().testCount.value) }
        compose.onNodeWithTag("nav-设置").performClick(); compose.onNodeWithTag("candidates-input").performTextReplacement("99")
        compose.onNodeWithTag("nav-测速").performClick(); compose.onNodeWithText("放弃并离开").performClick()
        compose.runOnIdle { assertEquals(71,vm().testCount.value) }
    }
    @Test fun invalidInlineSaveDoesNotLeaveOrClearDraft() {
        compose.onNodeWithTag("nav-设置").performClick(); compose.onNodeWithTag("candidates-input").performTextReplacement("")
        compose.onNodeWithText("请输入 1..10000 整数").assertExists()
        compose.onNodeWithTag("settings-save").performClick(); compose.onNodeWithTag("nav-设置").assertIsSelected()
        compose.onNodeWithTag("candidates-input").assertTextContains("")
        compose.runOnIdle { assertTrue(vm().draftPending.value) }
    }
    @Test fun duplicateLiteralAndCidrRenderOnceWithUnchangedRawSource() {
        val content="192.0.2.1\n192.0.2.1\n192.0.2.0/31\n192.0.2.0/31\n2001:db8::1"
        compose.runOnIdle { vm().loadIpRanges(content) }
        compose.onNodeWithTag("nav-列表").performClick()
        compose.onNodeWithText("重复条目只展示一次",substring=true).assertExists()
        compose.onNodeWithText("192.0.2.1",useUnmergedTree=true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("192.0.2.1"),vm().selectedSpecifiedIpSegments.value);assertEquals(content,vm().currentListContent.value) }
        compose.onNodeWithText("全选当前 family").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("192.0.2.1","192.0.2.0/31"),vm().selectedSpecifiedIpSegments.value) }
    }
    @Test fun narrowDarkLargeFontKeepsMainActionVisibleWithoutScroll() {
        val vm=vm(); val density=compose.activity.resources.displayMetrics.density
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density,1.8f)) {
                com.cfst.app.ui.theme.CfstTheme(dark=true) {
                    Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("narrow-root")) {
                        LocalNavigation(vm,{},{},{},{},{},{},{})
                    }
                }
            }
        } }
        compose.onNodeWithTag("primary-run-action").assertIsDisplayed()
        compose.onNodeWithTag("nav-列表").performClick(); compose.onNodeWithTag("nav-列表").assertIsSelected()
        compose.onNodeWithTag("nav-设置").performClick(); compose.onNodeWithTag("settings-save").assertIsDisplayed()
    }
    @Test fun recoveryIsIndependentScrollableAndHasNoNormalVm() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            com.cfst.app.ui.theme.CfstTheme(dark=true) { RecoveryScreen("损坏来源保留。".repeat(100),{},{}) }
        } }
        compose.onNodeWithText("选择有效状态备份").performScrollTo().assertIsDisplayed()
    }
}
