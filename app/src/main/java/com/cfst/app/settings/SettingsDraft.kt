package com.cfst.app.settings

import com.cfst.app.utils.DataUnits

data class SettingsDraft(
    val candidates:String, val repetitions:String, val concurrency:String, val downloads:String,
    val seconds:String, val port:String, val url:String, val budgetMb:String, val speedMb:String,
    val latency:String, val enableDownload:Boolean, val originalBudget:Long, val originalSpeed:Double,
    val budgetDirty:Boolean=false, val speedDirty:Boolean=false,
    val mirror:String="", val useMirror:Boolean=false,
    val runTimeSeconds:String="120", val sampleSeed:String="0", val randomizeSampleSeed:Boolean=true
) {
    fun validate():Map<String,Any> {
        fun integer(text:String,name:String,range:IntRange):Int {
            val value=text.toIntOrNull(); require(value!=null && value in range) { "$name 范围 ${range.first}..${range.last}" }; return value
        }
        val target=try { java.net.URI(url) } catch (_:Exception) { throw IllegalArgumentException("无效 HTTPS URL") }
        require(url.length<=4096 && target.scheme=="https" && !target.host.isNullOrEmpty() && target.userInfo==null && target.fragment==null) { "需要安全 HTTPS URL（无身份信息或fragment）" }
        val budget=if(budgetDirty) DataUnits.parseBudgetMb(budgetMb) else originalBudget
        val speed=if(speedDirty) speedMb.toDoubleOrNull()?.times(1000000) else originalSpeed
        require(speed!=null && speed.isFinite() && speed in 0.0..1e12) { "速度应为0..1e12 B/s" }
        val delay=latency.toDoubleOrNull(); require(delay!=null && delay.isFinite() && delay in 0.0..9999.0) { "延迟应为0..9999 ms" }
        if(mirror.isNotEmpty()) {
            val uri=try { java.net.URI(mirror) } catch (_:Exception) { throw IllegalArgumentException("镜像 URL 无效") }
            require(mirror.length<=4096 && uri.scheme=="https" && !uri.host.isNullOrEmpty() && uri.userInfo==null && uri.fragment==null) { "镜像只允许安全HTTPS" }
        }
        val seed=sampleSeed.toLongOrNull(); require(seed!=null) { "采样seed须为64位整数" }
        return mapOf("randomizeSampleSeed" to randomizeSampleSeed,"runTimeSeconds" to integer(runTimeSeconds,"总运行秒",30..600),"sampleSeed" to seed,"testCount" to integer(candidates,"候选数",1..10000),"pingCount" to integer(repetitions,"重复次数",1..20),
            "maxConcurrentPings" to integer(concurrency,"并发",1..16),"downloadTestCount" to integer(downloads,"下载候选上限",1..100),
            "downloadDuration" to integer(seconds,"时长秒",1..60),"downloadPort" to integer(port,"端口",1..65535),
            "downloadUrl" to url,"budgetBytes" to budget,"speedLimit" to (speed/1048576).toString(),"minBytesPerSecond" to speed,
            "latencyLimit" to delay.toString(),"enableDownloadTest" to enableDownload,"proxyUrl" to mirror,"useProxy" to useMirror)
    }
}
