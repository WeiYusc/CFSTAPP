package com.cfst.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Static integration guard, explicitly NOT touch/render acceptance. */
class UiIntegrationWiringTest {
    private fun source(path:String)=File("src/main/java/com/cfst/app/$path").readText()
    @Test fun navigationUsesThemeSelectedItemsBackDraftDialogAndFixedOwnerActions() {
        val s=source("MainActivity.kt")
        for(t in listOf("CfstTheme","NavigationBarItem","NavigationSession","BackHandler","saveAndLeave","discardAndLeave","stay","TestActionBar","DocumentOperationBanner","imePadding")) assertTrue("missing wiring $t",s.contains(t))
    }
    @Test fun listDisplayDeduplicatesWithoutReplacingRawSourceAndExposesSelectionActions() {
        val s=source("ui/lists/IpListScreen.kt")
        for(t in listOf("segments.distinct()","重复条目只展示一次","selectAllSpecifiedIpSegments","clearSpecifiedIpSegments","toggleable","canMutate")) assertTrue("missing list wiring $t",s.contains(t))
    }
    @Test fun historyConfirmsStableIdentityAndStartsCollapsed() {
        val s=source("ui/history/HistoryScreen.kt")
        for(t in listOf("historyIdentities","deleteHistoryIdentity","AlertDialog","不可撤销","expanded","key=","onExport(id)")) assertTrue("missing history wiring $t",s.contains(t))
    }
    @Test fun cardsUseTypedMetricsAndDetailsInsteadOfRawZero() {
        val s=source("ui/components/RunCards.kt")
        for(t in listOf("presentResult","AlertDialog","复制 IP","实际 Peer","terminationLabel")) assertTrue("missing result wiring $t",s.contains(t))
        assertFalse(s.contains("download?.bytesPerSecond ?: result.downloadSpeed*1048576"))
    }
    @Test fun settingsAndRecoveryRemainScrollableWithReachableActionsAndErrors() {
        val s=source("ui/settings/AdvancedSettingsScreen.kt")
        for(t in listOf("isError","supportingText","KeyboardType","ImeAction","settings-save","imePadding","vm.updateDraft")) assertTrue("missing settings wiring $t",s.contains(t))
        val recovery=source("RecoveryScreen.kt")
        assertTrue(recovery.contains("verticalScroll")); assertTrue(recovery.contains("DocumentOperationBanner")); assertTrue(recovery.contains("safeDrawingPadding"))
        assertTrue("long result details must scroll",source("ui/components/RunCards.kt").contains("verticalScroll"))
        val xml=File("src/main/res/values/themes.xml").readText()
        assertFalse("fixed blue system bars contradict app theme",xml.contains("#1976D2"))
        assertTrue(File("src/main/res/values-night/themes.xml").isFile)
    }
}
