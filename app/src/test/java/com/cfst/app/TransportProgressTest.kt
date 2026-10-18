package com.cfst.app

import com.cfst.app.speedtest.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class TransportProgressTest {
    @Test fun partialReadTimeoutReportsLiveConsumedBytesBeforeTermination() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(200000)).throttleBody(8192,3,TimeUnit.SECONDS))
            val live=CountDownLatch(1); var consumed=0L
            val method=DownloadTransport::class.java.methods.firstOrNull { it.name=="measure" && it.parameterCount==8 }
            assertNotNull("real network-source byte callback required",method)
            val result=method!!.invoke(DownloadTransport(),"127.0.0.1",server.url("/").toString(),server.port,4000L,ByteBudget(200000),RunCancellation(),true,{ n:Long -> consumed+=n; live.countDown(); Unit }) as DownloadMeasurement
            assertTrue(live.await(0,TimeUnit.SECONDS)); assertTrue(consumed>0); assertEquals(consumed,result.bytes)
            assertEquals(DownloadTermination.READ_TIMEOUT,result.termination); assertFalse(result.recommended)
        }
    }
    @Test fun repeatedFiniteBodiesCountEveryRequestInSameAttempt() {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody("x".repeat(1000))) }
            val result=DownloadTransport().measure("127.0.0.1",server.url("/").toString(),server.port,2000,ByteBudget(2500),RunCancellation(),true)
            assertEquals(2500,result.bytes); assertEquals(3,server.requestCount); assertEquals(DownloadTermination.BYTE_LIMIT,result.termination)
        }
    }
}
