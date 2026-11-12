package com.cfst.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UiReleaseVersionTest {
    @Test fun uiCandidateHasConsistent123Identity() {
        val config=File("build.gradle").readText()
        assertTrue(config.contains("versionName \"1.2.3\""))
        assertTrue(config.contains("versionCode 115"))
        assertTrue(File("src/main/java/com/cfst/app/ui/settings/AdvancedSettingsScreen.kt").readText().contains("CFST Local 1.2.3"))
    }
}
