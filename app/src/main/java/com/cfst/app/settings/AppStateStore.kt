package com.cfst.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.google.gson.*
import java.io.File

/** Single authoritative snapshot; compatibility getters never read legacy prefs after migration. */
class AppStateStore private constructor(context: Context, initial:Map<String,Any?>?) : SharedPreferences {
    constructor(context:Context):this(context,null)
    companion object {
        const val MAX_STATE_BYTES=32*1024*1024
        fun readBoundedUtf8(input:java.io.InputStream,limit:Int=MAX_STATE_BYTES):String {
            require(limit>=0)
            val bytes=java.io.ByteArrayOutputStream()
            val buffer=ByteArray(8192)
            while(true) {
                val n=input.read(buffer,0,minOf(buffer.size,limit-bytes.size()+1))
                if(n<0) break
                require(bytes.size().toLong()+n<=limit) { "状态超过大小上限" }
                bytes.write(buffer,0,n)
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        }
        private val stores=java.util.WeakHashMap<Context,AppStateStore>()
        @Synchronized fun get(context:Context):AppStateStore {
            val app=context.applicationContext
            return stores.getOrPut(app) { AppStateStore(app) }
        }
        fun validatePayload(context:Context,text:String) {
            val validator=AppStateStore(context,emptyMap())
            validator.validate(validator.decode(text))
        }
        /** Only called after explicit user confirmation; preserve bad bytes before replacement. */
        @Synchronized fun recover(context:Context,text:String):Boolean {
            val validator=AppStateStore(context,emptyMap())
            val next=validator.decode(text); validator.validate(next)
            val archive=File(context.filesDir,"state-recovery/${java.util.UUID.randomUUID()}")
            require(archive.mkdirs()) { "无法保留损坏来源" }
            for(name in listOf("app-state.json","app-state.json.bak","app-state.json.new")) {
                val source=File(context.filesDir,name)
                if(source.exists()) source.inputStream().use { input -> File(archive,name).outputStream().use { output -> input.copyTo(output); output.flush(); output.fd.sync() } }
            }
            if(!validator.write(next)) return false
            stores.remove(context.applicationContext)
            return true
        }
    }
    private val file = AtomicFile(File(context.filesDir,"app-state.json"))
    private var values: Map<String,Any?>
    var warning: String = ""; private set
    init {
        val authorityExists=file.baseFile.exists() || File(context.filesDir,"app-state.json.bak").exists()
        values = initial ?: try {
            file.openRead().use { decode(readBoundedUtf8(it)).also(::validate) }
        } catch (_: Exception) {
            val legacy = context.getSharedPreferences("local_settings",0).all.toMutableMap()
            if(authorityExists) throw IllegalStateException("权威状态损坏或超过上限；已保留文件，不会回退旧设置。请恢复有效备份")
            val cache = context.getSharedPreferences("ip_cache",0).all
            cache.forEach { (k,v) -> legacy[k] = v }
            val sanitized = sanitize(legacy)
            check(write(sanitized)) { "迁移保存失败，旧设置保留" }
            sanitized
        }
    }
    private fun sanitize(raw: Map<String,Any?>): Map<String,Any?> {
        val out=raw.toMutableMap()
        fun int(key:String, default:Int, range:IntRange) {
            val n=raw[key] as? Int
            out[key] = if (n != null && n in range) n else { if (raw.containsKey(key)) warning += "$key 已恢复安全默认；"; default }
        }
        int("testCount",30,1..10000); int("pingCount",3,1..20); int("downloadTestCount",3,1..100)
        int("runTimeSeconds",120,30..600); out["sampleSeed"]=(raw["sampleSeed"] as? Long) ?: 0L
        out["randomizeSampleSeed"]=(raw["randomizeSampleSeed"] as? Boolean) ?: true
        out["samplingMigrationNoticePending"]=if(raw["randomizeSampleSeed"] !is Boolean) raw.isNotEmpty() else (raw["samplingMigrationNoticePending"] as? Boolean) ?: false
        int("downloadDuration",3,1..60); int("downloadPort",443,1..65535); int("budgetMiB",3,1..1024)
        val concurrency=raw["maxConcurrentPings"] as? Int
        out["maxConcurrentPings"] = concurrency?.coerceIn(1,16) ?: 8
        if (concurrency != null && concurrency !in 1..16) warning += "并发已限制为16；"
        for ((k,default) in mapOf("enableDownloadTest" to true,"sortByLatency" to false,"useIpv6" to false,"useSpecifiedIpSegments" to false,"useProxy" to false)) {
            out[k]=(raw[k] as? Boolean) ?: default
        }
        for ((k,cap) in mapOf("speedLimit" to (1e12/1048576),"latencyLimit" to 9999.0)) {
            val n=(raw[k] as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..cap } ?: 0.0
            out[k]=n.toString()
        }
        val url=raw["downloadUrl"] as? String
        out["downloadUrl"] = url?.takeIf { validUrl(it) } ?: "https://speed.cloudflare.com/__down?bytes=1048576"
        out["proxyUrl"]=(raw["proxyUrl"] as? String)?.takeIf { it.isEmpty() || validUrl(it) } ?: ""
        out["selectedSegments"]=(raw["selectedSegments"] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet<String>()
        val validSelection=(out["selectedSegments"] as Set<*>).filterIsInstance<String>().filter { com.cfst.app.utils.IpRangeParser.parseCidr(it)!=null }.toSet()
        out["selectedSegments"]=validSelection
        if(validSelection.isEmpty()) out["useSpecifiedIpSegments"]=false
        out["budgetBytes"]=(out["budgetMiB"] as Int)*1048576L
        out["minBytesPerSecond"]=(out["speedLimit"] as String).toDouble()*1048576
        for(k in listOf("ipv4_file_content","ipv6_file_content","ip_file_content")) {
            val text=raw[k]
            if(text!=null) {
                val valid=try { text is String && com.cfst.app.utils.IpRangeParser.parseIpFile(text).isNotEmpty() } catch(_:Exception) { false }
                if(!valid) { out["legacy_invalid_$k"]=text.toString(); out.remove(k); warning+="旧列表无效，恢复内置；" }
            }
        }
        out["legacy_backup"]=Gson().toJson(raw)
        if (raw.isNotEmpty()) warning += "设置已迁移；并发初筛现在生效，历史配置未知。"
        return out
    }
    private fun validUrl(value:String):Boolean = try {
        val uri=java.net.URI(value)
        value.length<=4096 && uri.scheme=="https" && !uri.host.isNullOrEmpty() && uri.userInfo==null && uri.fragment==null
    } catch (_: Exception) { false }
    private fun encode(state:Map<String,Any?>):String {
        val root=JsonObject(); root.addProperty("schemaVersion",2)
        val fields=JsonObject()
        for ((key,value) in state) {
            val node=JsonObject()
            when(value) {
                is Int -> { node.addProperty("type","int"); node.addProperty("value",value) }
                is Long -> { node.addProperty("type","long"); node.addProperty("value",value) }
                is Float -> { node.addProperty("type","float"); node.addProperty("value",value) }
                is Double -> { node.addProperty("type","double"); node.addProperty("value",value) }
                is Boolean -> { node.addProperty("type","boolean"); node.addProperty("value",value) }
                is String -> { node.addProperty("type","string"); node.addProperty("value",value) }
                is Set<*> -> { node.addProperty("type","set"); node.add("value",Gson().toJsonTree(value)) }
                else -> continue
            }
            fields.add(key,node)
        }
        root.add("fields",fields); return root.toString()
    }
    private fun decode(text:String):Map<String,Any?> {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_STATE_BYTES)
        val root=JsonParser.parseString(text).asJsonObject
        require(root["schemaVersion"]?.isJsonPrimitive==true && root["schemaVersion"].asJsonPrimitive.isNumber && root["schemaVersion"].asBigDecimal==java.math.BigDecimal(2))
        val decoded=root["fields"].asJsonObject.entrySet().associate { (key,element) ->
            val node=element.asJsonObject; val value=node["value"]
            val type=node["type"].asString
            if(type!="set") {
                require(value.isJsonPrimitive)
                val p=value.asJsonPrimitive
                require(when(type) { "string" -> p.isString; "boolean" -> p.isBoolean; "int","long","float","double" -> p.isNumber; else -> false }) { "Wrong primitive $key" }
                if(type=="int") require(p.asBigDecimal.toBigIntegerExact().bitLength()<32)
                if(type=="long") require(p.asBigDecimal.toBigIntegerExact().bitLength()<64)
            } else require(value.isJsonArray && value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
            key to when(node["type"].asString) {
                "int" -> value.asInt; "long" -> value.asLong; "float" -> value.asFloat; "double" -> value.asDouble
                "boolean" -> value.asBoolean; "string" -> value.asString
                "set" -> value.asJsonArray.map { it.asString }.toSet()
                else -> throw IllegalArgumentException("Unknown state type")
            }
        }.toMutableMap()
        if(!decoded.containsKey("runTimeSeconds")) decoded["runTimeSeconds"]=120
        if(!decoded.containsKey("sampleSeed")) decoded["sampleSeed"]=0L
        // Validate explicit fields before normalization; never hide malformed pending flags.
        for(key in listOf("randomizeSampleSeed","samplingMigrationNoticePending"))
            if(decoded.containsKey(key)) require(decoded[key] is Boolean) { "Invalid $key" }
        if(!decoded.containsKey("randomizeSampleSeed")) {
            decoded["randomizeSampleSeed"]=true
            decoded["samplingMigrationNoticePending"]=true
        } else if(!decoded.containsKey("samplingMigrationNoticePending")) decoded["samplingMigrationNoticePending"]=false
        return decoded
    }
    private fun write(state:Map<String,Any?>):Boolean {
        var stream:java.io.FileOutputStream?=null
        return try {
            val bytes=encode(state).toByteArray(Charsets.UTF_8)
            require(bytes.size<=MAX_STATE_BYTES) { "状态超过有界上限，未写入" }
            stream=file.startWrite(); stream.write(bytes); stream.flush(); stream.fd.sync()
            file.finishWrite(stream); true
        } catch (_: Exception) { if (stream!=null) file.failWrite(stream); false }
    }
    @Synchronized fun exportState():String = encode(values)
    fun putDouble(editor:SharedPreferences.Editor,key:String,value:Double) { require(editor is Editor); editor.changes[key]=value }
    @Synchronized fun restoreState(payload:String):Boolean {
        val next=decode(payload)
        validate(next)
        if(!write(next)) return false
        values=next.toMap(); return true
    }
    private fun validate(next:Map<String,Any?>) {
        if(next.containsKey("sourceGeneration")) require(next["sourceGeneration"] is Long && (next["sourceGeneration"] as Long)>=0)
        for(k in listOf("source","ipv4_update_time","ipv6_update_time","ip_update_time","ip_version_info")) if(next.containsKey(k)) require(next[k] is String)
        if(next.containsKey("history_run_ids")) require(next["history_run_ids"] is String)
        (next["history_run_ids"] as? String)?.let { text ->
            val ids=JsonParser.parseString(text)
            require(ids.isJsonArray && ids.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
            val names=ids.asJsonArray.map { it.asString }; require(names.distinct().size==names.size)
            val rows=SnapshotRecords.histories(next["history_records"] as String)
            val runs=SnapshotRecords.runs(next["run_records"] as String)
            require(names.size==rows.size)
            require(runs.all { run -> val index=names.indexOf(run.runId); index>=0 && rows[index].size==run.results.size && rows[index].toSet()==run.results.toSet() })
        }
        (next["history_records"] as? String)?.let { SnapshotRecords.histories(it) }
        (next["run_records"] as? String)?.let { SnapshotRecords.runs(it) }
        fun int(key:String,range:IntRange) { require((next[key] as? Int)?.let { it in range } == true) { "Invalid $key" } }
        int("runTimeSeconds",30..600); require(next["sampleSeed"] is Long)
        require(next["randomizeSampleSeed"] is Boolean && next["samplingMigrationNoticePending"] is Boolean)
        int("testCount",1..10000); int("pingCount",1..20); int("maxConcurrentPings",1..16)
        int("downloadTestCount",1..100); int("downloadDuration",1..60); int("downloadPort",1..65535)
        require((next["budgetBytes"] as? Long)?.let { it in 1..1073741824L }==true)
        require((next["minBytesPerSecond"] as? Double)?.let { it.isFinite() && it in 0.0..1e12 }==true)
        require((next["downloadUrl"] as? String)?.let(::validUrl)==true)
        for(k in listOf("enableDownloadTest","sortByLatency","useIpv6","useSpecifiedIpSegments","useProxy")) require(next[k] is Boolean)
        for((k,cap) in mapOf("speedLimit" to 1e12/1048576,"latencyLimit" to 9999.0)) require((next[k] as? String)?.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..cap }==true)
        val legacySpeed=(next["speedLimit"] as String).toDouble()*1048576
        require(kotlin.math.abs((next["minBytesPerSecond"] as Double)-legacySpeed)<=kotlin.math.max(1e-6,legacySpeed*1e-12)) { "Conflicting speed units" }
        require((next["proxyUrl"] as? String)?.let { it.isEmpty() || validUrl(it) }==true)
        require(next["selectedSegments"] is Set<*> && (next["selectedSegments"] as Set<*>).all { it is String && com.cfst.app.utils.IpRangeParser.parseCidr(it)!=null })
        val ipv6=next["useIpv6"] as Boolean
        val selection=(next["selectedSegments"] as Set<*>).filterIsInstance<String>()
        require(selection.all { com.cfst.app.utils.IpRangeParser.parseCidr(it)?.isIpv6==ipv6 })
        require(next["useSpecifiedIpSegments"]!=true || selection.isNotEmpty())
        val source=next[if(ipv6) "ipv6_file_content" else "ipv4_file_content"] ?: next["ip_file_content"]
        if(source is String) {
            val lines=source.replace("\uFEFF","").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
            require(selection.all { it in lines }) { "选择不属于当前来源" }
        }
        for(k in listOf("ipv4_file_content","ipv6_file_content","ip_file_content")) if(next.containsKey(k)) {
            require(next[k] is String) { "Invalid list type $k" }
            com.cfst.app.utils.IpRangeParser.parseIpFile(next[k] as String)
        }
        for(k in listOf("history_records","run_records")) {
            if(next.containsKey(k)) require(next[k] is String)
            (next[k] as? String)?.let { text ->
                val list=JsonParser.parseString(text)
                require(list.isJsonArray && list.asJsonArray.size()<=100) { "Invalid bounded history" }
                if(k=="run_records") list.asJsonArray.forEach { entry ->
                    require(entry.isJsonObject)
                    val r=entry.asJsonObject
                    require(r["schemaVersion"]?.asInt==2 && r["runId"]?.asJsonPrimitive?.isString==true)
                    java.util.UUID.fromString(r["runId"].asString)
                    require(r["summary"]?.isJsonObject==true && r["events"]?.isJsonArray==true && r["results"]?.isJsonArray==true)
                    require(r["events"].asJsonArray.size()<=100000 && r["results"].asJsonArray.size()<=10000)
                    val summary=r["summary"].asJsonObject
                    for(field in listOf("plannedCandidates","generatedUnique","probeNotStarted","probeActive","probePassed","probeFailed","probeCancelled","downloadsStarted","downloadActive","downloadSucceeded","downloadFailed","downloadCancelled","uniqueDownloadCandidates","probePassedNotDownloaded","recommended","bodyBytesConsumed","elapsedNanos")) {
                        val n=summary[field]; require(n?.isJsonPrimitive==true && n.asJsonPrimitive.isNumber && n.asBigDecimal.signum()>=0)
                        n.asBigDecimal.toBigIntegerExact()
                    }
                    if(r["config"]?.isJsonNull==false) {
                        val c=r["config"].asJsonObject
                        require(c["testCount"].asInt in 1..10000 && c["pingCount"].asInt in 1..20 && c["maxConcurrentPings"].asInt in 1..16)
                        require(c["downloadDuration"].asLong in 1..60000 && c["byteBudget"].asLong in 1..1073741824L)
                        require(c["downloadUrl"]?.asString?.let(::validUrl)==true)
                    }
                    r["results"].asJsonArray.forEach { result -> require(result.isJsonObject && result.asJsonObject["ipAddress"]?.asJsonPrimitive?.isString==true && com.cfst.app.utils.IpRangeParser.literalBytes(result.asJsonObject["ipAddress"].asString)!=null) }
                    r["events"].asJsonArray.forEach { event -> require(event.isJsonObject && event.asJsonObject["identity"]?.isJsonObject==true && event.asJsonObject["identity"].asJsonObject["runId"]?.asString==r["runId"].asString) }
                }
                if(k=="history_records") list.asJsonArray.forEach { row ->
                    require(row.isJsonArray && row.asJsonArray.size()<=10000)
                    row.asJsonArray.forEach { result ->
                        require(result.isJsonObject)
                        val r=result.asJsonObject; val ip=r["ipAddress"]
                        require(ip!=null && ip.isJsonPrimitive && ip.asJsonPrimitive.isString && com.cfst.app.utils.IpRangeParser.literalBytes(ip.asString)!=null)
                        for(field in listOf("packetsSent","packetsReceived","packetLoss","avgLatency","downloadSpeed")) r[field]?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble.isFinite() && it.asDouble>=0) }
                    }
                }
            }
        }
    }
    @Synchronized override fun getAll():MutableMap<String,*> = values.toMutableMap()
    override fun getString(key:String?,defValue:String?):String? = values[key] as? String ?: defValue
    override fun getStringSet(key:String?,defValues:MutableSet<String>?):MutableSet<String>? = (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
    override fun getInt(key:String?,defValue:Int):Int = values[key] as? Int ?: defValue
    override fun getLong(key:String?,defValue:Long):Long = (values[key] as? Number)?.toLong() ?: defValue
    override fun getFloat(key:String?,defValue:Float):Float = (values[key] as? Number)?.toFloat() ?: defValue
    override fun getBoolean(key:String?,defValue:Boolean):Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key:String?):Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit():SharedPreferences.Editor = Editor()
    inner class Editor : SharedPreferences.Editor {
        val changes=mutableMapOf<String,Any?>(); private var clear=false
        override fun putString(key:String?,value:String?)=apply { changes[requireNotNull(key)]=value }
        override fun putStringSet(key:String?,values:MutableSet<String>?)=apply { changes[requireNotNull(key)]=values?.toSet() }
        override fun putInt(key:String?,value:Int)=apply { changes[requireNotNull(key)]=value }
        override fun putLong(key:String?,value:Long)=apply { changes[requireNotNull(key)]=value }
        override fun putFloat(key:String?,value:Float)=apply { changes[requireNotNull(key)]=value }
        override fun putBoolean(key:String?,value:Boolean)=apply { changes[requireNotNull(key)]=value }
        override fun remove(key:String?)=apply { changes[requireNotNull(key)]=null }
        override fun clear()=apply { clear=true }
        override fun commit():Boolean = synchronized(this@AppStateStore) {
            val latest=try { file.openRead().use { decode(readBoundedUtf8(it)).also(::validate) } } catch(_:Exception) { return@synchronized false }
            val next=if(clear) mutableMapOf() else latest.toMutableMap()
            changes.forEach { (k,v) -> if(v==null) next.remove(k) else next[k]=v }
            try { validate(next) } catch(_:Exception) { return@synchronized false }
            if(write(next)) { values=next.toMap(); true } else false
        }
        override fun apply() { check(commit()) { "无法保存状态，未提交" } }
    }
}
