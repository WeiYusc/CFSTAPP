package com.cfst.app.documents

import android.app.Application
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.util.UUID
import java.util.concurrent.*

/** Application-scope single IO slot: timeout quarantines rather than spawning replacement IO. */
class DocumentOperationOwner private constructor(private val app:Application,private val timeoutMs:Long=30000) {
    enum class Stage { IDLE,PREPARING,READY,PICKER,READING,PREVIEW,APPLYING,WRITING,DONE,FAILED,CANCELLED,QUARANTINED }
    data class Operation(val id:String,val sourceGeneration:Long,val stage:Stage,val message:String="",val snapshot:String?=null,val kind:String="LIST")
    private val _state=MutableStateFlow<Operation?>(null)
    val state=_state.asStateFlow()
    private val worker=Executors.newSingleThreadExecutor()
    private val timer=Executors.newSingleThreadScheduledExecutor()
    private var busy=false
    @Synchronized fun blocksStart():Boolean = busy || _state.value?.stage in setOf(Stage.PREPARING,Stage.READY,Stage.PICKER,Stage.READING,Stage.PREVIEW,Stage.APPLYING,Stage.WRITING,Stage.QUARANTINED)
    @Synchronized fun claimReady():Operation? {
        val op=_state.value ?: return null
        if(op.stage!=Stage.READY) return null
        _state.value=op.copy(stage=Stage.PICKER); persist(); return _state.value?.takeIf { it.stage==Stage.PICKER }
    }
    fun invalidateImport(generation:Long) {
        val op=synchronized(this) { _state.value?.takeIf { it.kind=="LIST" && it.sourceGeneration!=generation } } ?: return
        if(op.stage==Stage.PICKER) cancelPicker(op.id)
        else if(op.stage==Stage.PREVIEW) finishPreview(op.id,false)
        else if(op.stage==Stage.READING) cancelOperation(op.id)
    }
    fun cancelCurrent() {
        val op=state.value ?: return
        when(op.stage) {
            Stage.PICKER -> cancelPicker(op.id)
            Stage.PREVIEW -> finishPreview(op.id,false)
            Stage.READY -> synchronized(this) { _state.value=_state.value!!.copy(stage=Stage.CANCELLED,message="已取消导出准备"); op.snapshot?.let { File(dir,it).delete() }; File(app.filesDir,"pending-csv/${op.id}.csv").delete(); persist() }
            Stage.PREPARING -> synchronized(this) { _state.value=_state.value!!.copy(stage=Stage.QUARANTINED,message="准备已取消，等待worker退出"); persist() }
            Stage.READING,Stage.WRITING -> cancelOperation(op.id)
            else -> Unit
        }
    }
    private var closeable:Closeable?=null
    private var signal:CancellationSignal?=null
    private val dir=File(app.filesDir,"document-operations").apply { mkdirs() }
    @Synchronized fun picker(generation:Long):String? {
        if(blocksStart()) return null
        val id=UUID.randomUUID().toString(); _state.value=Operation(id,generation,Stage.PICKER)
        persist(); return id.takeIf { _state.value?.stage==Stage.PICKER }
    }
    @Synchronized fun cancelPicker(id:String?) {
        if(_state.value?.id!=id || _state.value?.stage!=Stage.PICKER) return
        _state.value?.snapshot?.let { File(dir,it).delete() }; File(dir,"$id.source").delete(); _state.value=_state.value!!.copy(stage=Stage.CANCELLED,message="已取消，当前列表未改变"); persist()
    }
    @Synchronized private fun start(id:String,stage:Stage):Boolean {
        if(busy || _state.value?.id!=id || _state.value?.stage!=Stage.PICKER) return false
        busy=true; signal=CancellationSignal(); _state.value=_state.value!!.copy(stage=stage); persist(); return true
    }
    private fun timeout(id:String):ScheduledFuture<*> = timer.schedule({ cancelOperation(id) },timeoutMs,TimeUnit.MILLISECONDS)
    private fun providerAvailable(uri:Uri):Boolean {
        val client=app.contentResolver.acquireContentProviderClient(uri) ?: return false
        client.close(); return true
    }
    private fun openInput(uri:Uri):InputStream? {
        if(uri.scheme=="content" && providerAvailable(uri)) {
            val descriptor=app.contentResolver.openAssetFileDescriptor(uri,"r",signal) ?: return null
            return try { descriptor.createInputStream() } catch(e:Exception) { descriptor.close(); throw e }
        }
        return app.contentResolver.openInputStream(uri)
    }
    private fun openOutput(uri:Uri):OutputStream? {
        if(uri.scheme=="content" && providerAvailable(uri)) {
            val descriptor=app.contentResolver.openFileDescriptor(uri,"wt",signal) ?: return null
            return android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        }
        return app.contentResolver.openOutputStream(uri,"wt")
    }
    @Synchronized private fun alive(id:String):Boolean = _state.value?.id==id && _state.value?.stage!=Stage.QUARANTINED
    fun cancelOperation(id:String) {
        val resources=synchronized(this) {
            if(_state.value?.id!=id || !busy) return
            _state.value=_state.value!!.copy(stage=Stage.QUARANTINED,message="文档操作取消/超时；提供者IO退出前槽位隔离，目标可能不完整，请换提供者或重启APP")
            persist(); signal to closeable
        }
        resources.first?.cancel()
        runCatching { resources.second?.close() }
    }
    fun read(id:String,uri:Uri) = readInternal(id,uri,false)
    fun readState(id:String,uri:Uri) = readInternal(id,uri,true)
    private fun readInternal(id:String,uri:Uri,fullState:Boolean) {
        if(!start(id,Stage.READING)) return
        synchronized(this) { _state.value=_state.value!!.copy(kind=if(fullState) "STATE" else "LIST"); persist() }
        worker.execute {
            val deadline=timeout(id)
            try {
                val input=openInput(uri) ?: throw IOException("无法读取文件，请重新选择")
                synchronized(this) { closeable=input }
                val bytes=ByteArrayOutputStream()
                input.use { stream ->
                    val buffer=ByteArray(8192)
                    while(true) {
                        check(alive(id)) { "文档读取已取消" }
                        val n=stream.read(buffer); if(n<0) break
                        require(bytes.size()+n<=if(fullState) com.cfst.app.settings.AppStateStore.MAX_STATE_BYTES else 1048576) { "输入超过大小上限" }; bytes.write(buffer,0,n)
                    }
                }
                val data=bytes.toByteArray()
                val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data)).toString()
                if(fullState) {
                    com.cfst.app.settings.AppStateStore.validatePayload(app,text)
                } else com.cfst.app.utils.IpRangeParser.parseIpFile(text)
                synchronized(this) {
                    if(alive(id)) {
                        val source=File(dir,"$id.source")
                        source.outputStream().use { it.write(data); it.flush(); it.fd.sync() }
                        _state.value=_state.value!!.copy(stage=Stage.PREVIEW,snapshot=source.name,message="文件读取完成，请预览并确认")
                        persist()
                    }
                }
            } catch(e:Exception) { deadline.cancel(false); synchronized(this) { closeable=null; signal=null; busy=false; fail(id,e) } }
            finally { deadline.cancel(false); synchronized(this) { if(_state.value?.id==id) { closeable=null; signal=null; busy=false; if(_state.value?.stage==Stage.QUARANTINED) { File(dir,"$id.source").delete(); _state.value=_state.value!!.copy(stage=Stage.CANCELLED,message="操作已取消，请重新选择"); persist() } } } }
        }
    }
    @Synchronized fun preview(id:String):ByteArray? {
        val op=_state.value ?: return null
        if(op.id!=id || op.stage!=Stage.PREVIEW) return null
        return op.snapshot?.let { File(dir,it).takeIf { f -> f.exists() && f.length()<=if(op.kind=="STATE") com.cfst.app.settings.AppStateStore.MAX_STATE_BYTES else 1048576 }?.readBytes() }
    }
    @Synchronized fun finishPreview(id:String,applied:Boolean) {
        if(_state.value?.id!=id || _state.value?.stage!=Stage.PREVIEW) return
        File(dir,"$id.source").delete()
        _state.value=_state.value!!.copy(stage=if(applied) Stage.DONE else Stage.CANCELLED,message=if(applied) "列表已应用" else "已取消导入")
        persist()
    }
    fun confirmState(id:String,restore:(String)->Boolean,callback:(Boolean,String)->Unit) {
        val snapshot=synchronized(this) {
            val op=_state.value ?: return
            if(busy || op.id!=id || op.stage!=Stage.PREVIEW || op.kind!="STATE") return
            busy=true; _state.value=op.copy(stage=Stage.APPLYING); persist()
            op.snapshot
        }
        worker.execute {
            var ok=false; var message="恢复失败，原状态未改变"
            try {
                val text=File(dir,requireNotNull(snapshot)).inputStream().use { com.cfst.app.settings.AppStateStore.readBoundedUtf8(it) }
                com.cfst.app.settings.AppStateStore.validatePayload(app,text)
                synchronized(this) { if(_state.value?.id==id && _state.value?.stage==Stage.APPLYING) ok=restore(text) }
                if(ok) message="状态已恢复"
            } catch(e:Exception) { message="恢复失败：${e.message?.take(512)}" }
            synchronized(this) {
                if(_state.value?.id==id) { busy=false; File(dir,"$id.source").delete(); _state.value=_state.value!!.copy(stage=if(ok) Stage.DONE else Stage.FAILED,message=message); persist() }
            }
            callback(ok,message)
        }
    }
    @Synchronized fun prepareExport(generation:Long,text:String):String? {
        val id=picker(generation) ?: return null
        val source=File(dir,"$id.source")
        source.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush(); it.fd.sync() }
        _state.value=_state.value!!.copy(snapshot=source.name); persist(); return id
    }
    fun prepareExportAsync(generation:Long,text:String,callback:(String?,String?)->Unit) = prepareExportAsync(generation,text,"CSV",callback)
    fun prepareExportAsync(generation:Long,text:String,kind:String,callback:(String?,String?)->Unit={_,_->}) {
        require(kind in setOf("CSV","STATE"))
        val id=synchronized(this) {
            val reserved=picker(generation)
            if(reserved!=null && _state.value?.stage==Stage.PICKER) { busy=true; _state.value=_state.value!!.copy(stage=Stage.PREPARING,kind=kind); persist() }
            if(reserved!=null && _state.value?.stage!=Stage.PREPARING) { busy=false; _state.value=_state.value?.copy(stage=Stage.FAILED,message="导出准备保存失败，未启动worker") }
            reserved?.takeIf { _state.value?.stage==Stage.PREPARING }
        }
        if(id==null) { callback(null,"已有文档操作"); return }
        worker.execute {
            try {
                val source=File(dir,"$id.source")
                source.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush(); it.fd.sync() }
                if(kind=="CSV") {
                    val csvDir=File(app.filesDir,"pending-csv").apply { mkdirs() }
                    File(csvDir,"$id.csv").outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
                }
                synchronized(this) {
                    if(_state.value?.id!=id || _state.value?.stage==Stage.QUARANTINED) {
                        source.delete(); File(app.filesDir,"pending-csv/$id.csv").delete(); busy=false
                        if(_state.value?.id==id) { _state.value=_state.value!!.copy(stage=Stage.CANCELLED,message="准备已取消"); persist() }
                    } else { _state.value=_state.value!!.copy(snapshot=source.name,stage=Stage.READY); busy=false; persist() }
                }
                callback(id,null)
            } catch(e:Exception) { synchronized(this) { busy=false; fail(id,e) }; callback(null,e.message ?: "无法准备源快照") }
        }
    }
    @Synchronized fun setExportKind(id:String,kind:String) {
        require(kind in setOf("CSV","STATE"))
        if(_state.value?.id==id) { _state.value=_state.value!!.copy(kind=kind); persist() }
    }
    @Synchronized fun canRetryExport():Boolean {
        val op=_state.value ?: return false
        if(busy || op.stage!=Stage.FAILED || op.kind !in setOf("CSV","STATE")) return false
        val source=op.snapshot?.let { File(dir,it) } ?: return false
        return source.isFile && System.currentTimeMillis()-source.lastModified()<=TimeUnit.HOURS.toMillis(24)
    }
    @Synchronized fun retryExport(id:String):String? {
        val op=_state.value ?: return null
        if(busy || op.id!=id || op.stage!=Stage.FAILED) return null
        val source=op.snapshot?.let { File(dir,it) } ?: return null
        if(!source.isFile || System.currentTimeMillis()-source.lastModified()>TimeUnit.HOURS.toMillis(24)) return null
        val next=UUID.randomUUID().toString()
        _state.value=op.copy(id=next,stage=Stage.PICKER,message="请选择新位置重试；旧目标可能不完整")
        persist(); return next
    }
    fun write(id:String,uri:Uri) {
        if(!start(id,Stage.WRITING)) return
        worker.execute {
            val deadline=timeout(id)
            try {
                val source=_state.value?.snapshot?.let { File(dir,it) } ?: throw IOException("源快照缺失，请重新选择")
                source.inputStream().use { input ->
                    val output=openOutput(uri) ?: throw IOException("无法打开目标")
                    synchronized(this) { closeable=output }
                    output.use { stream ->
                        val buffer=ByteArray(8192)
                        while(true) { check(alive(id)) { "导出已取消" }; val n=input.read(buffer); if(n<0) break; stream.write(buffer,0,n) }
                        stream.flush()
                    }
                }
                synchronized(this) { if(alive(id)) { source.delete(); closeable=null; signal=null; busy=false; _state.value=_state.value!!.copy(stage=Stage.DONE,message="导出已保存"); persist() } }
            } catch(e:Exception) { deadline.cancel(false); synchronized(this) { closeable=null; signal=null; busy=false; fail(id,e) } }
            finally { deadline.cancel(false); synchronized(this) { if(_state.value?.id==id) { closeable=null; signal=null; busy=false; if(_state.value?.stage==Stage.QUARANTINED) { File(dir,"$id.source").delete(); _state.value=_state.value!!.copy(stage=Stage.CANCELLED,message="操作已取消，请重新选择"); persist() } } } }
        }
    }
    @Synchronized private fun fail(id:String,e:Exception) {
        if(alive(id)) { _state.value=_state.value!!.copy(stage=Stage.FAILED,message="操作失败：${e.message?.take(512)}；目标可能不完整，不会自动删除，可重新选位置重试（源保留24h）。"); persist() }
    }
    private fun persist() {
        val op=_state.value ?: return
        val atomic=android.util.AtomicFile(File(dir,"operation.json")); var stream:FileOutputStream?=null
        try { stream=atomic.startWrite(); stream.write(com.google.gson.Gson().toJson(op).toByteArray()); stream.fd.sync(); atomic.finishWrite(stream) }
        catch(_:Exception) {
            if(stream!=null) runCatching { atomic.failWrite(stream) }
            _state.value=op.copy(stage=if(busy) Stage.QUARANTINED else Stage.FAILED,message="文档状态保存失败；在途IO退出前槽位隔离，请重新选择")
        }
    }
    init {
        dir.listFiles()?.filter { it.name.endsWith(".source") && System.currentTimeMillis()-it.lastModified()>TimeUnit.HOURS.toMillis(24) }?.forEach { it.delete() }
        try {
            val op=com.google.gson.Gson().fromJson(File(dir,"operation.json").readText(),Operation::class.java)
            _state.value=when(op.stage) {
                Stage.WRITING -> op.copy(stage=Stage.FAILED,message="上次导出中断，目标可能不完整；请重新选择位置，不会重放导出")
                Stage.PREPARING,Stage.READING,Stage.APPLYING,Stage.QUARANTINED -> { File(dir,"${op.id}.source").delete(); op.copy(stage=Stage.FAILED,message="上次准备/导入中断，请重新选择") }
                else -> op
            }
        } catch(_:Exception) { }
    }
    companion object {
        private val owners=java.util.WeakHashMap<Application,DocumentOperationOwner>()
        @Synchronized fun get(app:Application):DocumentOperationOwner = owners.getOrPut(app) { DocumentOperationOwner(app) }
    }
}
