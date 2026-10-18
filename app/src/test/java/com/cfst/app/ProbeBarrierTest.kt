package com.cfst.app

import com.cfst.app.speedtest.*
import com.cfst.app.model.*
import com.cfst.app.utils.IpRangeParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.*

class ProbeBarrierTest {
    @Test fun realSocketBarrierBoundsPeakAndJoinsBeforeRelease() = runBlocking {
        ServerSocket(0,50,InetAddress.getByName("0.0.0.0")).use { server ->
            val barrier=CountDownLatch(4); val release=CountDownLatch(1)
            val active=java.util.concurrent.atomic.AtomicInteger(); val peak=java.util.concurrent.atomic.AtomicInteger()
            val engine=SpeedTestEngine()
            val method=TcpPingTest::class.java.declaredClasses.first { it.simpleName=="Companion" }.methods.firstOrNull { it.name=="setConnectionObserver" }
            assertNotNull("socket barrier observation missing",method)
            val observer:(Socket)->Unit={ socket -> assertTrue(socket.isConnected); val n=active.incrementAndGet(); peak.accumulateAndGet(n,::maxOf); barrier.countDown(); release.await(3,TimeUnit.SECONDS); active.decrementAndGet() }
            method!!.invoke(TcpPingTest.Companion,observer)
            val accept=Thread { repeat(8) { server.accept().use {} } }.apply { start() }
            try {
                val job=launch(Dispatchers.Default) { engine.startTest(IpRangeParser.parseIpFile((1..8).joinToString("\n") { "127.0.0.$it" }),SpeedTestEngine.TestConfig(testCount=8,pingCount=1,port=server.localPort,downloadTest=false,maxConcurrentPings=4),{},{},{}) }
                assertTrue(barrier.await(3,TimeUnit.SECONDS)); assertEquals(4,peak.get()); assertTrue(engine.isRunning())
                release.countDown(); job.join(); assertEquals(0,active.get()); assertFalse(engine.isRunning())
            } finally { release.countDown(); method.invoke(TcpPingTest.Companion,null); accept.join(1000) }
        }
    }
}
