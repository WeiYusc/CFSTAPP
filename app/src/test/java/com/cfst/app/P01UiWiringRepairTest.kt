package com.cfst.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source wiring checks only; Compose runtime acceptance remains instrumentation-pending. */
class P01UiWiringRepairTest {
    private fun source(path:String)=File("src/main/java/com/cfst/app/$path").readText()
    @Test fun homeHasQuickDraftInputsPresetsAndAdaptiveColumns() {
        val text=source("ui/test/TestScreen.kt")
        for(token in listOf("presetDraft","省流","标准","深入","quick-candidates","quick-downloads","quick-budget","saveDraft","BoxWithConstraints","maxWidth","取消快捷草稿")) assertTrue("missing home wiring $token",text.contains(token))
    }
    @Test fun advancedGroupsHaveActualRememberedFoldState() {
        val text=source("ui/settings/AdvancedSettingsScreen.kt")
        assertTrue(text.contains("rememberSaveable")); assertTrue(text.contains("if(expanded")); assertTrue(text.contains("onClick={expanded"))
    }
    @Test fun listSelectionAndBusyCancellationUseProductionOwners() {
        val text=source("ui/lists/IpListScreen.kt")
        for(token in listOf("selectedSpecifiedIpSegments","availableIpSegments","toggleSpecifiedIpSegment","useSpecifiedIpSegments","editPaste","取消粘贴编辑")) assertTrue("missing list owner wiring $token",text.contains(token))
        val activity=source("MainActivity.kt")
        assertTrue(activity.contains("DocumentOperationBanner")); assertTrue(activity.contains("cancelCurrent"))
        val banner=source("ui/components/DocumentOperationBanner.kt")
        assertTrue(banner.contains("取消文档操作")); assertTrue(banner.contains("p.canCancel"))
        val presentation=source("ui/presentation/DocumentPresentation.kt")
        assertTrue("preparing/ready cancellation remains available",presentation.contains("Stage.PREPARING,DocumentOperationOwner.Stage.READY"))
    }
}
