package com.cfst.app

import com.cfst.app.speedtest.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class DownloadOutcomeTest {
    private fun property(value: DownloadMeasurement, name: String): Any? {
        val getter=value.javaClass.methods.firstOrNull { it.name == "get$name" }
        assertNotNull("measurement must expose $name",getter)
        return getter!!.invoke(value)
    }
    @Test fun byteLimitIsValidButShortAndNotRecommended() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(10000)))
            val result=DownloadTransport().measure("127.0.0.1",server.url("/").toString(),server.port,2000,ByteBudget(1000),RunCancellation(),true)
            assertEquals("BYTE_LIMIT",property(result,"Termination").toString())
            assertEquals(true,property(result,"TransportValid"))
            assertEquals("SHORT",property(result,"SampleQuality").toString())
            assertEquals(false,property(result,"Recommended"))
        }
    }
    @Test fun explicitDeadlineWithBytesIsNormalTermination() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(100000)).throttleBody(8192,2,TimeUnit.SECONDS))
            val result=DownloadTransport().measure("127.0.0.1",server.url("/").toString(),server.port,1100,ByteBudget(100000),RunCancellation(),true)
            assertTrue(result.bytes > 0)
            assertEquals("TIME_LIMIT",property(result,"Termination").toString())
            assertEquals(true,property(result,"TransportValid"))
        }
    }
    @Test fun partialFailureIsNeverValid() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(100000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val result=DownloadTransport().measure("127.0.0.1",server.url("/").toString(),server.port,3000,ByteBudget(100000),RunCancellation(),true)
            assertTrue(result.bytes > 0)
            assertEquals("IO_FAILURE",property(result,"Termination").toString())
            assertEquals(false,property(result,"TransportValid"))
        }
    }
    @Test fun firstStopReasonWinsAndIsTyped() {
        val token=RunCancellation(); token.cancel("network lost"); token.cancel("user")
        val result=DownloadTransport().measure("127.0.0.1","http://example.test/",80,1000,ByteBudget(100),token,true)
        assertEquals("NETWORK_LOST",property(result,"Termination").toString())
        assertEquals(false,property(result,"TransportValid"))
    }
    @Test fun emptyBodyDoesNotSpin() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(""))
            val result=DownloadTransport().measure("127.0.0.1",server.url("/").toString(),server.port,1000,ByteBudget(100),RunCancellation(),true)
            assertEquals(1,server.requestCount)
            assertEquals(false,property(result,"TransportValid"))
        }
    }
}
