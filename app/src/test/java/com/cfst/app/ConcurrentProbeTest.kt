package com.cfst.app

import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.*

class ConcurrentProbeTest {
    @Test fun workersCanConnectWhileCollectorIsBlockedAndDownloadsWait() = runBlocking {
        ServerSocket(0,50,InetAddress.getByName("0.0.0.0")).use { server ->
            val accepted=CountDownLatch(4)
            val listener=Thread { repeat(4) { server.accept().use { accepted.countDown() } } }.apply { start() }
            val activeCollectors=java.util.concurrent.atomic.AtomicInteger(0)
            val callbacks=java.util.concurrent.atomic.AtomicInteger(0)
            var first=true
            SpeedTestEngine().startTest(IpRangeParser.parseIpFile((1..4).joinToString("\n") { "127.0.0.$it" }), SpeedTestEngine.TestConfig(testCount=4,pingCount=1,downloadTest=false,port=server.localPort,maxConcurrentPings=4),{}, {
                // A serial coroutine may resume on a different IO worker; assert exclusion,
                // not thread affinity, while retaining the real socket concurrency gate.
                assertEquals("collector callbacks must never overlap",1,activeCollectors.incrementAndGet())
                try {
                    callbacks.incrementAndGet()
                    if (first) { first=false; assertTrue("workers must progress independently of collector",accepted.await(2,TimeUnit.SECONDS)) }
                } finally { activeCollectors.decrementAndGet() }
            },{})
            listener.join(1000)
            assertEquals(4,callbacks.get());assertEquals(0,activeCollectors.get())
        }
    }
    @Test fun outOfRangeConcurrencyRejectedBeforeNetwork() = runBlocking {
        for (n in listOf(0,17)) {
            try { SpeedTestEngine().startTest(IpRangeParser.parseIpFile("127.0.0.1"),SpeedTestEngine.TestConfig(maxConcurrentPings=n,downloadTest=false),{},{},{}); fail("invalid concurrency accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
