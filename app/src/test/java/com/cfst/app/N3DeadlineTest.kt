package com.cfst.app

import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class N3DeadlineTest {
    class Clock:RunDeadlineClock {
        @Volatile var now=1000000000L
        data class Task(val end:Long,val action:()->Unit,var joined:Boolean=false)
        val tasks=java.util.concurrent.CopyOnWriteArrayList<Task>()
        override fun nanoTime()=now
        override fun schedule(delayNanos:Long,expire:()->Unit):RunDeadlineClock.DeadlineTask {
            val task=Task(now+delayNanos,expire); tasks.add(task)
            return object:RunDeadlineClock.DeadlineTask { override fun cancelAndJoin() { task.joined=true } }
        }
        fun expire() { val task=tasks.last(); now=task.end; task.action() }
    }
    @Test fun realBlockedSocketIsClosedByOwnerDeadlineAndOwnerRestarts() {
        val clock=Clock(); val engine=SpeedTestEngine(clock)
        val config=SpeedTestEngine.TestConfig(testCount=1,downloadTest=false,runTimeSeconds=30)
        val ranges=IpRangeParser.parseIpFile("127.0.0.1")
        val token=engine.reserve(config,ranges)!!
        ServerSocket(0).use { server ->
            Socket("127.0.0.1",server.localPort).use { socket ->
                server.accept().use { peer ->
                    val entered=java.util.concurrent.CountDownLatch(1)
                    val unregister=token.register { socket.close() }
                    val worker=Thread { try { entered.countDown(); socket.getInputStream().read(); fail("blocked socket must close") } catch(_:java.io.IOException) {} }
                    worker.start(); assertTrue(entered.await(1,TimeUnit.SECONDS)); clock.expire(); worker.join(1000)
                    assertFalse(worker.isAlive); assertTrue(socket.isClosed); unregister()
                }
            }
        }
        runBlocking { engine.startTest(ranges,config,{},{},{},reserved=token) }
        assertEquals("RUN_TIME_LIMIT",engine.lastRecord!!.summary.stoppedReason)
        assertEquals(RunOwner.State.RELEASED,engine.runOwner!!.state)
        assertTrue(clock.tasks.first().joined)
        val next=engine.reserve(config,ranges)!!
        clock.tasks.first().action() // even an already-dispatched old callback only owns the old token.
        assertFalse(next.cancelled)
        engine.releaseRun(next)
        assertTrue(clock.tasks.last().joined)
    }
    @Test fun blockedRealCallRetainsPartialBytesAndAccurateTermination() {
        val clock=Clock(); val engine=SpeedTestEngine(clock)
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("x".repeat(200000)).throttleBody(70000,1,TimeUnit.SECONDS))
            val entered=java.util.concurrent.CountDownLatch(1)
            val config=SpeedTestEngine.TestConfig(testCount=1,pingCount=1,port=server.port,allowHttp=true,downloadUrl=server.url("/").toString(),downloadDuration=60000,runTimeSeconds=30)
            val worker=Thread { runBlocking { engine.startTest(IpRangeParser.parseIpFile("127.0.0.1"),config,{ if((engine.currentRecord?.summary?.bodyBytesConsumed ?: 0)>0) entered.countDown() },{},{}) } }
            worker.start()
            assertTrue(entered.await(5,TimeUnit.SECONDS)); clock.expire(); worker.join(1800)
            assertFalse("run deadline must cancel in-flight Call",worker.isAlive)
            val record=engine.lastRecord!!
            assertEquals("RUN_TIME_LIMIT",record.summary.stoppedReason)
            assertEquals(1,record.summary.downloadCancelled)
            val d=record.results.single().download!!
            assertTrue(d.bytes>0); assertEquals(DownloadTermination.RUN_TIME_LIMIT,d.termination); assertFalse(d.recommended)
            assertEquals(record.summary.bodyBytesConsumed,d.bytes)
            assertEquals(RunOwner.State.RELEASED,engine.runOwner!!.state)
        }
    }
    @Test fun reservationTimeCountsAndStopFirstReasonSurvivesTimerCleanup() {
        val clock=Clock(); val engine=SpeedTestEngine(clock)
        val config=SpeedTestEngine.TestConfig(downloadTest=false,runTimeSeconds=30)
        val ranges=IpRangeParser.parseIpFile("127.0.0.1")
        val token=engine.reserve(config,ranges)!!
        clock.now+=29_500_000_000L
        assertEquals(500L,token.remainingMillis(1000))
        engine.stopTest("USER_STOP"); clock.expire()
        runBlocking { engine.startTest(ranges,config,{},{},{},reserved=token) }
        assertEquals("USER_STOP",engine.lastRecord!!.summary.stoppedReason)
        assertEquals(30_000_000_000L,engine.lastRecord!!.summary.elapsedNanos)
    }
    @Test fun actualProductionTimerExpiresAtStandardMinimum() {
        val engine=SpeedTestEngine()
        val ranges=IpRangeParser.parseIpFile("127.0.0.1")
        val config=SpeedTestEngine.TestConfig(downloadTest=false,runTimeSeconds=30)
        val token=engine.reserve(config,ranges)!!
        val expired=java.util.concurrent.CountDownLatch(1)
        token.register { expired.countDown() }
        assertTrue("real timer must run without coroutine entry",expired.await(32,TimeUnit.SECONDS))
        assertEquals("RUN_TIME_LIMIT",token.reason)
        runBlocking { engine.startTest(ranges,config,{},{},{},reserved=token) }
        assertEquals(RunOwner.State.RELEASED,engine.runOwner!!.state)
    }
    @Test fun runRemainingRatherThanLocalLimitClassifiesBlockedCall() {
        val clock=Clock(); val engine=SpeedTestEngine(clock)
        val ranges=IpRangeParser.parseIpFile("127.0.0.1")
        val config=SpeedTestEngine.TestConfig(runTimeSeconds=30,downloadTest=false)
        val token=engine.reserve(config,ranges)!!
        clock.now+=29_900_000_000L
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val result=DownloadTransport().measure("127.0.0.1",server.url("/").toString(),server.port,10000,ByteBudget(100),token,true)
            assertEquals("shortened attempt is the run limit, not ordinary local completion",DownloadTermination.RUN_TIME_LIMIT,result.termination)
            assertEquals("RUN_TIME_LIMIT",token.reason)
        }
        engine.releaseRun(token)
    }
    @Test fun engineRegisteredSocketBlockedReadClosesAtDeadline() {
        val clock=Clock();val engine=SpeedTestEngine(clock)
        ServerSocket(0).use { server ->
            val entered=java.util.concurrent.CountDownLatch(1)
            var observed:Socket?=null
            TcpPingTest.connectionObserver={ socket -> observed=socket;entered.countDown();socket.getInputStream().read() }
            val worker=Thread { runBlocking { engine.startTest(IpRangeParser.parseIpFile("127.0.0.1"),SpeedTestEngine.TestConfig(testCount=1,pingCount=1,port=server.localPort,downloadTest=false,runTimeSeconds=30),{},{},{}) } }
            try {
                worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));clock.expire();worker.join(1800)
                assertFalse(worker.isAlive);assertTrue(observed!!.isClosed)
                assertEquals(1,engine.lastRecord!!.summary.probeCancelled)
                assertEquals("RUN_TIME_LIMIT",engine.lastRecord!!.summary.stoppedReason)
                assertEquals(RunOwner.State.RELEASED,engine.runOwner!!.state)
            } finally { TcpPingTest.connectionObserver=null;engine.stopTest() }
        }
    }
    @Test fun deadlineIsJoinedBeforePublishingTerminalRecordToCompletionCallback() {
        val clock=Clock();val engine=SpeedTestEngine(clock)
        val ranges=IpRangeParser.parseIpFile("127.0.0.1")
        val config=SpeedTestEngine.TestConfig(testCount=1,downloadTest=false,runTimeSeconds=30)
        val token=engine.reserve(config,ranges)!!
        engine.stopTest("USER_STOP")
        runBlocking { engine.startTest(ranges,config,{},{},{
            assertTrue("terminal callback must never race a still-live timer",clock.tasks.single().joined)
        },reserved=token) }
        assertEquals(RunOwner.State.RELEASED,engine.runOwner!!.state)
    }
    @Test fun invalidDeadlineDoesNotReserveOrStartNetwork() {
        for(seconds in listOf(0,29,601,Int.MAX_VALUE)) {
            val engine=SpeedTestEngine()
            try { engine.reserve(SpeedTestEngine.TestConfig(runTimeSeconds=seconds),IpRangeParser.parseIpFile("127.0.0.1")); fail("reject $seconds") } catch(_:IllegalArgumentException) {}
            assertFalse(engine.isRunning())
        }
    }
}
