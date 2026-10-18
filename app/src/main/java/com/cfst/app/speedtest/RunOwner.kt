package com.cfst.app.speedtest

import com.cfst.app.utils.IpRangeParser

class RunOwner(val id:String,val token:RunCancellation,private val clock:RunDeadlineClock=MonotonicRunClock) {
    var startedNanos:Long=0; private set
    private var deadline:RunDeadlineClock.DeadlineTask?=null
    fun finishDeadline() { deadline?.cancelAndJoin(); deadline=null }
    enum class State { RESERVED,RUNNING,STOPPING,TERMINAL,RELEASED }
    @Volatile var state=State.RESERVED; private set
    var config:SpeedTestEngine.TestConfig?=null; private set
    var parentRunId:String?=null; private set
    var list:List<IpRangeParser.IpRange> = emptyList(); private set
    var confirmedLiteral:String?=null; private set
    @Synchronized fun bind(config:SpeedTestEngine.TestConfig,list:List<IpRangeParser.IpRange>,parentRunId:String?=null,confirmedLiteral:String?=null) {
        check(state==State.RESERVED || state==State.STOPPING)
        require(config.runTimeSeconds in 30..600)
        confirmedLiteral?.let { literal ->
            require(parentRunId!=null && config.testCount==1 && config.downloadTestCount==1)
            require(IpRangeParser.literalBytes(literal)!=null && list==listOf(IpRangeParser.parseCidr(literal))) { "Retest literal differs from reserved singleton" }
        }
        this.config=config; this.list=list.toList(); this.parentRunId=parentRunId; this.confirmedLiteral=confirmedLiteral
        if(deadline==null) {
            startedNanos=clock.nanoTime()
            token.bindDeadline(startedNanos+java.util.concurrent.TimeUnit.SECONDS.toNanos(config.runTimeSeconds.toLong()),clock)
            deadline=clock.schedule(java.util.concurrent.TimeUnit.SECONDS.toNanos(config.runTimeSeconds.toLong())) { token.cancel("RUN_TIME_LIMIT") }
        }
    }
    @Volatile var entered:Boolean=false; private set
    @Synchronized fun running() { entered=true; if(!token.cancelled) state=State.RUNNING }
    @Synchronized fun stop(reason:String) {
        if(state==State.RELEASED || state==State.TERMINAL) return
        state=State.STOPPING; token.cancel(reason)
    }
    @Synchronized fun terminal() { if(state!=State.RELEASED) state=State.TERMINAL }
    @Synchronized fun release() { check(state==State.TERMINAL); state=State.RELEASED }
}
