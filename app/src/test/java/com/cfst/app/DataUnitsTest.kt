package com.cfst.app

import org.junit.Assert.*
import org.junit.Test

class DataUnitsTest {
    private fun units():Class<*> = try { Class.forName("com.cfst.app.utils.DataUnits") } catch (_:ClassNotFoundException) { fail("decimal units missing"); throw AssertionError() }
    @Test fun formatsDecimalBytesWithoutBinaryRelabeling() {
        val type=units(); val obj=type.getField("INSTANCE").get(null)
        val format=type.getMethod("formatBytes",java.lang.Long.TYPE)
        assertEquals("0 B",format.invoke(obj,0L)); assertEquals("999 B",format.invoke(obj,999L))
        assertEquals("1.00 KB",format.invoke(obj,1000L)); assertEquals("3.15 MB",format.invoke(obj,3145728L))
        assertEquals("1.00 GB",format.invoke(obj,1000000000L))
    }
    @Test fun decimalEditorRejectsOverflowAndExtraPrecision() {
        val type=units(); val obj=type.getField("INSTANCE").get(null)
        val parse=type.getMethod("parseBudgetMb",String::class.java)
        assertEquals(300000000L,parse.invoke(obj,"300"))
        for (input in listOf("", "-1","1.001","1073.75","999999999999999999999")) {
            try { parse.invoke(obj,input); fail("accepted $input") } catch (e:java.lang.reflect.InvocationTargetException) { assertTrue(e.cause is IllegalArgumentException) }
        }
    }
}
