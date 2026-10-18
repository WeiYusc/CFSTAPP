package com.cfst.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source wiring guards only; real Compose interaction remains device acceptance. */
class SamplingRouteUiRepairTest {
    private fun source(path:String)=File("src/main/java/com/cfst/app/$path").readText()
    @Test fun advancedEditorBindsModeAndPersistentMigrationNotice() {
        val s=source("ui/settings/AdvancedSettingsScreen.kt")
        assertTrue(s.contains("draft.randomizeSampleSeed"));assertTrue(s.contains("randomizeSampleSeed=it"))
        assertTrue(s.contains("!running && !draft.randomizeSampleSeed"))
        assertTrue(s.contains("samplingMigrationNoticePending.collectAsState()"));assertTrue(s.contains("acknowledgeSamplingMigration"))
    }
    @Test fun testAndRetestShowEffectiveVersusFutureSeedWithoutConsumingSource() {
        val s=source("ui/test/TestScreen.kt");val cards=source("ui/components/RunCards.kt")
        assertTrue(s.contains("randomizeSampleSeed.collectAsState()"));assertTrue(s.contains("samplingMigrationNoticePending.collectAsState()"))
        assertTrue(s.contains("acknowledgeSamplingMigration"));assertTrue(s.contains("resultConfig?.sampleSeed"))
        assertTrue(cards.contains("探索seed启动时生成"));assertTrue(cards.contains("randomizeSeed"))
    }
    @Test fun allRoutesWarnWithoutClaimingProxyBypass() {
        val s=source("ui/test/TestScreen.kt");val cards=source("ui/components/RunCards.kt")
        assertFalse(cards.contains("无代理"));assertTrue(cards.contains("仍受系统VPN/当前路由影响"))
        assertTrue(s.contains("结果反映当前路由"));assertTrue(s.contains("直连优选前关闭VPN/代理"))
        assertTrue(s.contains("已识别VPN"))
    }
}
