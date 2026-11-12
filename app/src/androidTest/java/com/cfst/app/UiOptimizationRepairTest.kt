package com.cfst.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.ui.settings.AdvancedSettingsScreen
import com.cfst.app.ui.test.TestScreen
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

open class UiOptimizationRepairTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: SpeedTestViewModel
    private var density = 1f
    private var originalState: String? = null

    private fun render(width: Int = 320, fontScale: Float = 1f, settings: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.runOnUiThread {
            vm = ViewModelProvider(store, SpeedTestViewModel.createFactory(app))[SpeedTestViewModel::class.java]
            originalState = com.cfst.app.settings.AppStateStore.get(app).exportState()
            vm.acknowledgeSamplingMigration()
        }
        density = compose.activity.resources.displayMetrics.density
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.requiredWidth(width.dp).fillMaxHeight()) {
                        if (settings) AdvancedSettingsScreen(vm) {} else TestScreen(vm) {}
                    }
                }
            }
        }
    }

    @After fun cleanUp() {
        compose.runOnUiThread { if (::vm.isInitialized) vm.stopTest() }
        if (::vm.isInitialized) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (vm.isTesting.value && System.nanoTime() < end) {
                compose.runOnIdle { }
                Thread.sleep(10)
            }
            assertFalse("test engine must release before restoring state", vm.isTesting.value)
        }
        compose.runOnUiThread {
            store.clear()
            originalState?.let { payload ->
                val app = ApplicationProvider.getApplicationContext<Application>()
                assertTrue("restore pre-test durable state", com.cfst.app.settings.AppStateStore.get(app).restoreState(payload))
                assertEquals(payload, com.cfst.app.settings.AppStateStore.get(app).exportState())
            }
        }
    }

    private fun assertPresetsFit(width: Int, scale: Float) {
        render(width, scale)
        for (label in listOf("省流", "标准", "深入")) {
            val button = compose.onNode(hasText(label) and hasClickAction())
            button.performScrollTo().assertIsDisplayed()
            val bounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue("$label touch height", bounds.height / density >= 47.9f)
            assertTrue("$label touch width", bounds.width / density >= 47.9f)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("real text layout required", layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertFalse("$label horizontal overflow at $width/$scale", layout.didOverflowWidth)
                assertFalse("$label vertical overflow at $width/$scale", layout.didOverflowHeight)
                assertEquals("preset label must stay on one line", 1, layout.lineCount)
            }
        }
    }
    @Test fun presets320Normal() = assertPresetsFit(320, 1f)
    @Test fun presets360Normal() = assertPresetsFit(360, 1f)
    @Test fun presets320Large() = assertPresetsFit(320, 1.8f)
    @Test fun presets360Large() = assertPresetsFit(360, 1.8f)

    private fun assertPreset(label: String, candidates: String, downloads: String, budget: String, seconds: String) {
        render()
        val before = vm.savedTestConfig()
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle {
            val draft = requireNotNull(vm.editableDraft.value)
            assertEquals(candidates, draft.candidates)
            assertEquals(downloads, draft.downloads)
            assertEquals(budget, draft.budgetMb)
            assertEquals(seconds, draft.seconds)
            assertTrue(vm.draftPending.value)
            assertEquals(before, vm.savedTestConfig())
        }
    }
    @Test fun economyCreatesRealDraft() = assertPreset("省流", "10", "1", "3", "3")
    @Test fun standardCreatesRealDraft() = assertPreset("标准", "30", "3", "30", "3")
    @Test fun deepCreatesRealDraft() = assertPreset("深入", "300", "10", "300", "5")

    @Test fun runningDisablesPresets() {
        render()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        java.net.ServerSocket(0).use { server ->
            com.cfst.app.speedtest.TcpPingTest.connectionObserver = { _ -> entered.countDown(); release.await(10, TimeUnit.SECONDS) }
            try {
                compose.runOnUiThread {
                    vm.updateUseIpv6(false)
                    vm.updateUseSpecifiedIpSegments(false)
                    vm.loadIpRanges("127.0.0.1")
                    vm.updateDownloadPort(server.localPort)
                    vm.updatePingCount(1)
                    vm.updateEnableDownloadTest(false)
                    vm.startTest()
                    assertTrue(vm.isTesting.value)
                }
                assertTrue("real engine socket entered", entered.await(5, TimeUnit.SECONDS))
                for (label in listOf("省流", "标准", "深入")) {
                    compose.onNode(hasText(label) and hasClickAction()).performScrollTo().assertIsNotEnabled()
                }
                compose.runOnIdle { assertNull(vm.editableDraft.value) }
            } finally {
                compose.runOnUiThread { vm.stopTest() }
                release.countDown()
                com.cfst.app.speedtest.TcpPingTest.connectionObserver = null
            }
        }
    }

    @Test fun allGroupsExposeButtonRoleTouchTargetAndToggle() {
        render(settings = true)
        for ((group, field) in listOf("探测" to "candidates-input", "资源" to "downloads-input", "筛选" to "speed-input", "安全端点" to "url-input")) {
            if (group == "探测") compose.onNodeWithText("探测 收起").performScrollTo().performClick()
            val collapsed = compose.onNodeWithText("$group 展开")
            collapsed.performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            val bounds = collapsed.fetchSemanticsNode().boundsInRoot
            assertTrue("$group height >=48dp", bounds.height / density >= 47.9f)
            assertTrue("$group width >=48dp", bounds.width / density >= 47.9f)
            compose.onNodeWithTag(field).assertDoesNotExist()
            collapsed.performClick()
            compose.onNodeWithTag(field).assertExists()
            compose.onNodeWithText("$group 收起").performScrollTo().performClick()
            compose.onNodeWithTag(field).assertDoesNotExist()
            compose.onNodeWithText("$group 展开").assertExists()
        }
    }
}
