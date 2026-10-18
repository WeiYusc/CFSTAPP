package com.cfst.app

import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.atomic.AtomicInteger

class SchedulingBenchmarkTest {
    /** SIMULATED scheduling only: injected post-connect delay is not TCP RTT/mobile performance. */
    @Test fun simulatedSchedulingOneVersusEight() = runBlocking {
        ServerSocket(0,100,InetAddress.getByName("0.0.0.0")).use { server ->
            val listener=Thread { try { while(!server.isClosed) server.accept().use {} } catch(_:Exception) {} }.apply { start() }
            val candidates=IpRangeParser.parseIpFile((1..16).joinToString("\n") { "127.0.0.$it" })
            val stats=mutableListOf<Triple<Int,Long,Int>>()
            try {
                for(concurrency in listOf(1,8)) {
                    val active=AtomicInteger(); val peak=AtomicInteger(); val requests=AtomicInteger()
                    TcpPingTest.connectionObserver={ val n=active.incrementAndGet(); peak.accumulateAndGet(n,::maxOf); requests.incrementAndGet(); try { Thread.sleep(40) } finally { active.decrementAndGet() } }
                    val engine=SpeedTestEngine(); val started=System.nanoTime()
                    engine.startTest(candidates,SpeedTestEngine.TestConfig(testCount=16,pingCount=1,port=server.localPort,downloadTest=false,maxConcurrentPings=concurrency),{},{},{})
                    val elapsed=(System.nanoTime()-started)/1000000
                    stats.add(Triple(concurrency,elapsed,peak.get()))
                    assertEquals(16,requests.get()); assertTrue(peak.get()<=concurrency); assertEquals(0,active.get())
                    println("SIMULATED scheduling only concurrency=$concurrency wallMs=$elapsed peak=${peak.get()} connections=${requests.get()}")
                }
                assertTrue(stats[1].third>1); assertTrue(stats[1].second<stats[0].second)
            } finally { TcpPingTest.connectionObserver=null; server.close(); listener.join(1000) }
        }
    }
}
