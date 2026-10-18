package com.cfst.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source wiring guards only; real Compose interaction remains device acceptance. */
class SamplingRouteUiRepairTest {
    private fun source(path:String)=File("src/main/java/com/cfst/app/$path").readText()
    @Test fun advancedEditorBindsModeAndPersistentMigrationNotice() {
        val s=source("ui/settings/AdvancedSettingsScreen.kt")
        assertFalse(s.contains("draft.randomizeSampleSeed"));assertFalse(s.contains("sample-seed-input"))
        assertTrue(s.contains("每轮自动采样"))
        assertTrue(s.contains("samplingMigrationNoticePending.collectAsState()"));assertTrue(s.contains("acknowledgeSamplingMigration"))
    }
    @Test fun testAndRetestShowEffectiveVersusFutureSeedWithoutConsumingSource() {
        val s=source("ui/test/TestScreen.kt");val cards=source("ui/components/RunCards.kt")
        assertFalse(s.contains("randomizeSampleSeed"));assertTrue(s.contains("samplingMigrationNoticePending.collectAsState()"))
        assertTrue(s.contains("acknowledgeSamplingMigration"));assertFalse(s.contains("sampleSeed"))
        assertFalse(cards.contains("seed",ignoreCase=true));assertTrue(cards.contains("仅复测已确认的单个 IP"))
    }
    @Test fun allRoutesWarnWithoutClaimingProxyBypass() {
        val s=source("ui/test/TestScreen.kt");val cards=source("ui/components/RunCards.kt")
        assertFalse(cards.contains("无代理"));assertTrue(cards.contains("仍受系统VPN/当前路由影响"))
        assertTrue(s.contains("结果反映当前路由"));assertTrue(s.contains("直连优选前关闭VPN/代理"))
        assertTrue(s.contains("已识别VPN"))
    }
}
