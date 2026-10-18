package com.cfst.app

import org.junit.Assert.*
import org.junit.Test

class UiInlineValidationTest {
    private fun error(text:String,max:Double):Any? {
        val type=Class.forName("com.cfst.app.ui.settings.AdvancedSettingsScreenKt")
        val method=type.declaredMethods.first { it.name=="decimalError" }.apply { isAccessible=true }
        return method.invoke(null,text,max)
    }
    @Test fun validDecimalDoesNotShowInlineError() { assertNull(error("0",9999.0));assertNull(error("1.25",9999.0)) }
    @Test fun invalidDecimalHasInlineError() { for(s in listOf("","NaN","Infinity","-1","10000")) assertNotNull(error(s,9999.0)) }
}
