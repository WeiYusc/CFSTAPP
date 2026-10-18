package com.cfst.app.ui.presentation

import com.cfst.app.documents.DocumentOperationOwner

data class DocumentPresentation(val label:String,val message:String,val canCancel:Boolean,val canRetry:Boolean,val busy:Boolean)
fun documentPresentation(owner:DocumentOperationOwner):DocumentPresentation {
    val op=owner.state.value ?: return documentStagePresentation(DocumentOperationOwner.Stage.IDLE,"LIST","",false)
    return documentStagePresentation(op.stage,op.kind,op.message,owner.canRetryExport())
}
fun documentStagePresentation(stage:DocumentOperationOwner.Stage,kind:String,message:String,retry:Boolean):DocumentPresentation {
    val label=when(stage) {
        DocumentOperationOwner.Stage.IDLE -> "无文档操作"
        DocumentOperationOwner.Stage.PREPARING -> "正在准备导出快照"
        DocumentOperationOwner.Stage.READY -> "快照就绪，等待选择位置"
        DocumentOperationOwner.Stage.PICKER -> "等待选择文件或保存位置"
        DocumentOperationOwner.Stage.READING -> "正在读取并校验文件"
        DocumentOperationOwner.Stage.PREVIEW -> "预览待确认，不会自动应用"
        DocumentOperationOwner.Stage.APPLYING -> "正在恢复状态，请等待完成；此阶段不可取消"
        DocumentOperationOwner.Stage.WRITING -> "正在写入，请等待关闭输出流"
        DocumentOperationOwner.Stage.QUARANTINED -> "文档槽位隔离，旧IO退出前不可重试；可换提供者或重启APP"
        DocumentOperationOwner.Stage.DONE -> "文档操作已完成"
        DocumentOperationOwner.Stage.CANCELLED -> "文档操作已取消"
        DocumentOperationOwner.Stage.FAILED -> "文档操作失败"
    }
    val canCancel=stage in setOf(DocumentOperationOwner.Stage.PREPARING,DocumentOperationOwner.Stage.READY,DocumentOperationOwner.Stage.PICKER,DocumentOperationOwner.Stage.READING,DocumentOperationOwner.Stage.PREVIEW,DocumentOperationOwner.Stage.WRITING)
    val busy=stage in setOf(DocumentOperationOwner.Stage.PREPARING,DocumentOperationOwner.Stage.READING,DocumentOperationOwner.Stage.APPLYING,DocumentOperationOwner.Stage.WRITING)
    return DocumentPresentation((when(kind) { "CSV"->"结果 CSV"; "STATE"->"完整状态"; else->"列表文件" })+" · "+label,message.take(512),canCancel,stage==DocumentOperationOwner.Stage.FAILED && retry,busy)
}
