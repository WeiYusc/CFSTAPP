package com.cfst.app

import com.cfst.app.speedtest.*
import com.cfst.app.model.*
import com.cfst.app.utils.IpRangeParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.*

class CollectorFailureTest {
    @Test fun collectorFailureClosesSocketsAndConservesTerminalSummary() = runBlocking {
        ServerSocket(0,50,InetAddress.getByName("0.0.0.0")).use { server ->
            val engine=SpeedTestEngine()
            var complete=false
            try {
                engine.startTest(IpRangeParser.parseIpFile((1..8).joinToString("\n") { "127.0.0.$it" }),SpeedTestEngine.TestConfig(testCount=8,pingCount=1,port=server.localPort,downloadTest=false,maxConcurrentPings=4),{}, { throw IllegalStateException("collector broke") },{complete=true})
                fail("collector exception swallowed")
            } catch(e:IllegalStateException) { assertEquals("collector broke",e.message) }
            assertTrue(complete); assertFalse(engine.isRunning())
            assertEquals(0,engine.lastRecord!!.summary.probeActive)
            assertEquals(engine.lastRecord!!.summary.generatedUnique,engine.lastRecord!!.summary.probeNotStarted+engine.lastRecord!!.summary.probePassed+engine.lastRecord!!.summary.probeFailed+engine.lastRecord!!.summary.probeCancelled)
            assertNotNull(engine.prepareRun())
        }
    }
    @Test fun tokenTypedReasonDoesNotDependOnLocalizedStrings() {
        val token=RunCancellation()
        val method=token.javaClass.methods.firstOrNull { it.name=="cancel" && it.parameterTypes.contentEquals(arrayOf(DownloadTermination::class.java)) }
        assertNotNull("typed winner API absent",method)
        method!!.invoke(token,DownloadTermination.NETWORK_LOST)
        token.cancel("user")
        assertEquals("NETWORK_LOST",token.reason)
    }
    @Test fun normalByteLimitCannotBeReclassifiedByLateStop() {
        val token=RunCancellation()
        token.cancel("BYTE_LIMIT"); token.cancel("user")
        val value=DownloadTransport().measure("127.0.0.1","http://example.test/",80,1000,ByteBudget(100),token,true)
        assertEquals(DownloadTermination.BYTE_LIMIT,value.termination)
    }
}
