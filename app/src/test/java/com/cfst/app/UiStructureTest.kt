package com.cfst.app

import org.junit.Assert.*
import org.junit.Test

/** UI compilation contract; touch acceptance is separately instrumented. */
class UiStructureTest {
    @Test fun eachDestinationHasItsOwnCompiledComposable() {
        for(name in listOf("com.cfst.app.ui.test.TestScreenKt","com.cfst.app.ui.lists.IpListScreenKt","com.cfst.app.ui.history.HistoryScreenKt","com.cfst.app.ui.settings.AdvancedSettingsScreenKt","com.cfst.app.ui.components.RunCardsKt")) {
            try { assertTrue(Class.forName(name).methods.any { it.parameterTypes.any { p -> p.name=="androidx.compose.runtime.Composer" } }) }
            catch(_:ClassNotFoundException) { fail("destination missing $name") }
        }
    }
}
