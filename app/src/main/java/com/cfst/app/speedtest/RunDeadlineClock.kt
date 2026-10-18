package com.cfst.app.speedtest

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Injection controls time, never the production 30..600 second validation. */
interface RunDeadlineClock {
    fun nanoTime():Long
    fun schedule(delayNanos:Long, expire:()->Unit):DeadlineTask
    interface DeadlineTask { fun cancelAndJoin() }
}
object MonotonicRunClock:RunDeadlineClock {
    override fun nanoTime()=System.nanoTime()
    override fun schedule(delayNanos:Long, expire:()->Unit):RunDeadlineClock.DeadlineTask {
        val timer=Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"cfst-run-deadline").apply { isDaemon=true } }
        return scheduleOn(timer,delayNanos,expire)
    }
    internal fun scheduleOn(timer:java.util.concurrent.ScheduledExecutorService,delayNanos:Long,expire:()->Unit):RunDeadlineClock.DeadlineTask {
        val future=try { timer.schedule(expire,delayNanos.coerceAtLeast(0),TimeUnit.NANOSECONDS) }
        catch(e:Exception) { timer.shutdownNow();throw e }
        return object:RunDeadlineClock.DeadlineTask {
            override fun cancelAndJoin() { future.cancel(false); timer.shutdownNow(); while(!timer.awaitTermination(1,TimeUnit.SECONDS)) { /* wait for the old token's closers */ } }
        }
    }
}
