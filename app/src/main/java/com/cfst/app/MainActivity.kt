package com.cfst.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.cfst.app.documents.DocumentOperationOwner
import com.cfst.app.viewmodel.SpeedTestViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingCsvId:String?=null
    private var pendingImportId:String?=null
    private var pendingDocumentExportId:String?=null
    private var stateRestore=false
    private var exportKind="CSV"
    private var statePreview:String? by mutableStateOf(null)
    private var recoveryRequired by mutableStateOf(false)
    private var recoveryMessage by mutableStateOf("权威状态损坏；原文件已保留。请选择有效schema备份并明确确认恢复。不会回退旧设置或自动联网。")
    private lateinit var pendingCsv:PendingCsvExport
    private lateinit var documents:DocumentOperationOwner
    private var preparingCsv=false
    private val vm:SpeedTestViewModel get()=ViewModelProvider(this,SpeedTestViewModel.createFactory(application))[SpeedTestViewModel::class.java]
    private val operationLaunchers=mutableMapOf<String,androidx.activity.result.ActivityResultLauncher<*>>()
    private fun listLauncher(id:String,restore:Boolean):androidx.activity.result.ActivityResultLauncher<Array<String>> {
        @Suppress("UNCHECKED_CAST")
        return operationLaunchers.getOrPut("read:$id") {
            activityResultRegistry.register("read:$id",ActivityResultContracts.OpenDocument()) { uri ->
                if(documents.state.value?.id!=id || documents.state.value?.stage!=DocumentOperationOwner.Stage.PICKER) return@register
                if(uri==null) documents.cancelPicker(id) else if(restore) documents.readState(id,uri) else documents.read(id,uri)
            }
        } as androidx.activity.result.ActivityResultLauncher<Array<String>>
    }
    private fun exportLauncher(id:String,kind:String):androidx.activity.result.ActivityResultLauncher<String> {
        @Suppress("UNCHECKED_CAST")
        return operationLaunchers.getOrPut("write:$id") {
            activityResultRegistry.register("write:$id",ActivityResultContracts.CreateDocument(if(kind=="CSV") "text/csv" else "application/json")) { uri ->
                if(documents.state.value?.id!=id || documents.state.value?.stage!=DocumentOperationOwner.Stage.PICKER) return@register
                if(uri==null) documents.cancelPicker(id)
                else if(kind=="CSV" && !java.io.File(filesDir,"pending-csv/$pendingCsvId.csv").isFile) { val error="保存失败：待导出 CSV 快照缺失，请重新导出"; reportStatus(error); Toast.makeText(this,error,Toast.LENGTH_LONG).show(); documents.cancelPicker(id) }
                else documents.write(id,uri)
            }
        } as androidx.activity.result.ActivityResultLauncher<String>
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        documents=DocumentOperationOwner.get(application); pendingCsv=PendingCsvExport(application)
        recoveryRequired=try { com.cfst.app.settings.AppStateStore.get(application); false } catch(_:IllegalStateException) { true }
        pendingCsvId=savedInstanceState?.getString("pendingCsvId"); pendingImportId=savedInstanceState?.getString("pendingImportId")
        pendingDocumentExportId=savedInstanceState?.getString("pendingDocumentExportId")
        stateRestore=savedInstanceState?.getBoolean("stateRestore") ?: false; exportKind=savedInstanceState?.getString("exportKind") ?: "CSV"
        pendingImportId?.let { listLauncher(it,stateRestore) }
        pendingDocumentExportId?.let { exportLauncher(it,exportKind) }
        pendingCsv.cleanup(pendingCsvId)
        lifecycleScope.launch { documents.state.collect { op ->
            if(op?.stage==DocumentOperationOwner.Stage.READY && !isDestroyed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) launchReadyExport()
            if(op?.stage==DocumentOperationOwner.Stage.PREVIEW) {
                if(op.kind=="STATE") statePreview=op.id
                else if(vm.importPreview.value==null) {
                    if(op.sourceGeneration!=vm.sourceGeneration) documents.finishPreview(op.id,false)
                    else documents.preview(op.id)?.let { vm.previewDocument(it,op.sourceGeneration); if(vm.importPreview.value==null) documents.finishPreview(op.id,false) }
                }
            } else if(op?.message?.isNotEmpty()==true) reportStatus(op.message)
            if(op?.id==pendingDocumentExportId && op?.stage in setOf(DocumentOperationOwner.Stage.DONE,DocumentOperationOwner.Stage.CANCELLED)) {
                if(op?.stage==DocumentOperationOwner.Stage.DONE) Toast.makeText(this@MainActivity,if(exportKind=="CSV") "CSV 已保存" else "状态已导出",Toast.LENGTH_SHORT).show()
                pendingCsv.discard(pendingCsvId); pendingCsvId=null; pendingDocumentExportId=null
            }
            if(op?.id==pendingDocumentExportId && op?.stage==DocumentOperationOwner.Stage.FAILED) Toast.makeText(this@MainActivity,"保存失败：${op.message}",Toast.LENGTH_LONG).show()
        } }
        setContent { com.cfst.app.ui.theme.CfstTheme {
            if(recoveryRequired) RecoveryScreen(recoveryMessage,::requestStateRestore) { documents.cancelCurrent();statePreview=null }
            else LocalNavigation(vm,::requestCsvExport,::requestListImport,::requestStateExport,::requestStateRestore,::retryDocumentExport,::requestHistoryIdentityExport) { applied -> documents.state.value?.let { documents.finishPreview(it.id,applied) } }
            if(statePreview!=null) AlertDialog(onDismissRequest={documents.state.value?.let { documents.finishPreview(it.id,false) };statePreview=null},title={Text("恢复完整状态？")},text={Text("替换设置、列表、来源、选择与历史。原损坏文件独立保留，不删除。不是CSV；旧APK直接降级数据不保证兼容。")},confirmButton={TextButton(onClick=::confirmStateRestore) { Text("确认恢复状态") }},dismissButton={TextButton(onClick={documents.state.value?.let { documents.finishPreview(it.id,false) };statePreview=null}) { Text("取消恢复") }})
        } }
    }
    override fun onStop() { if(!recoveryRequired) vm.stopTest("离开前台，已停止"); super.onStop() }
    private fun reportStatus(text:String) { if(recoveryRequired) recoveryMessage=text else vm.reportDocumentStatus(text) }
    internal fun confirmStateRestore() {
        val id=statePreview ?: documents.state.value?.takeIf { it.kind=="STATE" && it.stage==DocumentOperationOwner.Stage.PREVIEW }?.id ?: return
        val recovery=recoveryRequired
        statePreview=null
        documents.confirmState(id,{ text -> if(recovery) com.cfst.app.settings.AppStateStore.recover(application,text) else vm.restoreAppState(text) }) { ok,message ->
            lifecycleScope.launch {
                if(ok && recovery) { viewModelStore.clear(); recoveryRequired=false }
                reportStatus(message)
            }
        }
    }
    override fun onResume() { super.onResume(); if(::documents.isInitialized) launchReadyExport() }
    private fun launchReadyExport() {
        val op=documents.claimReady() ?: return
        pendingDocumentExportId=op.id; exportKind=op.kind
        try {
            if(op.kind=="CSV") {
                pendingCsvId=op.id
                check(java.io.File(filesDir,"pending-csv/${op.id}.csv").isFile) { "CSV 源快照缺失" }
                exportLauncher(op.id,op.kind).launch("cfst-local-results.csv")
            } else exportLauncher(op.id,op.kind).launch("cfst-state-v2.json")
        } catch(e:Exception) { documents.cancelPicker(op.id); vm.reportDocumentStatus("导出失败：${e.message}") }
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("pendingCsvId",pendingCsvId); outState.putString("pendingImportId",pendingImportId)
        outState.putString("pendingDocumentExportId",pendingDocumentExportId); outState.putBoolean("stateRestore",stateRestore); outState.putString("exportKind",exportKind)
        super.onSaveInstanceState(outState)
    }
    internal fun requestListImport() {
        stateRestore=false; pendingImportId=documents.picker(vm.sourceGeneration)
        if(pendingImportId!=null) listLauncher(pendingImportId!!,false).launch(arrayOf("text/*","application/octet-stream"))
        else vm.reportDocumentStatus("已有文档操作，请完成或取消；提供者卡住时请重启APP")
    }
    internal fun requestCsvExport() {
        requestCsvSnapshot(vm.exportResults())
    }
    private fun requestHistoryExport(index:Int) { requestCsvSnapshot(vm.exportHistory(index)) }
    private fun requestHistoryIdentityExport(id:String) {
        try { requestCsvSnapshot(vm.exportHistoryIdentity(id)) } catch(e:Exception) { reportStatus(e.message ?: "记录已不存在") }
    }
    private fun requestCsvSnapshot(snapshot:String) {
        if(pendingCsvId!=null || documents.blocksStart()) { reportStatus("已有文档操作，请完成或取消；IO退出前不可开始新导出"); return }
        documents.prepareExportAsync(0,snapshot,"CSV") { _,error -> if(error!=null) lifecycleScope.launch { reportStatus(error) } }
    }
    internal fun requestStateExport() {
        try { val snapshot=vm.exportAppState(); documents.prepareExportAsync(0,snapshot,"STATE") }
        catch(e:Exception) { vm.reportDocumentStatus("导出失败：${e.message}") }
    }
    internal fun requestStateRestore() {
        stateRestore=true; pendingImportId=documents.picker(if(recoveryRequired) 0 else vm.sourceGeneration)
        if(pendingImportId!=null) listLauncher(pendingImportId!!,true).launch(arrayOf("application/json","text/*"))
        else reportStatus("已有文档操作，请完成或取消；IO退出前不可开始恢复")
    }
    private fun retryDocumentExport() {
        val old=documents.state.value ?: return
        exportKind=old.kind
        pendingDocumentExportId=documents.retryExport(old.id)
        if(pendingDocumentExportId!=null) { exportLauncher(pendingDocumentExportId!!,exportKind).launch(if(exportKind=="CSV") "cfst-local-results.csv" else "cfst-state-v2.json") }
    }
    override fun onDestroy() {
        operationLaunchers.values.forEach { it.unregister() }; operationLaunchers.clear()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LocalNavigation(vm:SpeedTestViewModel,onExport:()->Unit,onImport:()->Unit,onStateExport:()->Unit,onStateRestore:()->Unit,onRetry:()->Unit,onHistoryExport:(String)->Unit,onImportFinish:(Boolean)->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val owner=remember { DocumentOperationOwner.get(context.applicationContext as android.app.Application) }
    val operation by owner.state.collectAsState()
    val pastePending by vm.pastePending.collectAsState()
    val dirty by vm.draftPending.collectAsState()
    var savedPage by rememberSaveable { mutableStateOf("测速") }
    val navigation=remember(vm) { com.cfst.app.ui.presentation.NavigationSession(vm,savedPage) }
    val page=navigation.page
    SideEffect { savedPage=page }
    androidx.activity.compose.BackHandler(enabled=page!="测速" || dirty) {
        if(page=="测速") navigation.resumeEditor() else navigation.navigate("测速")
    }
    Scaffold(topBar={TopAppBar(title={Text(page)})},bottomBar={
        Column(Modifier.imePadding()) {
            if(page=="测速") com.cfst.app.ui.test.TestActionBar(vm) {
                if(dirty) navigation.resumeEditor() else navigation.navigate(if(operation?.kind in setOf("CSV","STATE")) "历史" else "列表")
            }
            NavigationBar {
                val labels=listOf("测速","列表","历史","设置")
                val icons=listOf(androidx.compose.material.icons.Icons.Outlined.Speed,androidx.compose.material.icons.Icons.Outlined.List,androidx.compose.material.icons.Icons.Outlined.History,androidx.compose.material.icons.Icons.Outlined.Settings)
                labels.forEachIndexed { index,label -> NavigationBarItem(selected=page==label,onClick={navigation.navigate(label)},icon={Icon(icons[index],contentDescription=label)},label={Text(label)},modifier=Modifier.testTag("nav-$label")) }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp)) {
            com.cfst.app.ui.components.DocumentOperationBanner(owner,{owner.cancelCurrent();vm.cancelImport()},onRetry)
            if(pastePending) TextButton(onClick=vm::cancelImport) { Text("取消未确认粘贴") }
            when(page) {
                "列表" -> com.cfst.app.ui.lists.IpListScreen(vm,onImport,onImportFinish)
                "历史" -> com.cfst.app.ui.history.HistoryScreen(vm,onStateExport,onStateRestore,onRetry,onHistoryExport)
                "设置" -> com.cfst.app.ui.settings.AdvancedSettingsScreen(vm) { navigation.navigate("测速") }
                else -> com.cfst.app.ui.test.TestScreen(vm,onExport)
            }
        }
    }
    if(navigation.pendingPage!=null) AlertDialog(onDismissRequest=navigation::stay,title={Text("配置尚未保存")},text={
        Column { Text("保存后离开，或放弃修改；不会静默使用旧配置测速。")
            val status by vm.statusMessage.collectAsState(); Text(status.take(512))
        }
    },confirmButton={TextButton(onClick={navigation.saveAndLeave()}) { Text("保存并离开") }},dismissButton={
        Column { TextButton(onClick=navigation::discardAndLeave) { Text("放弃并离开") }; TextButton(onClick=navigation::stay) { Text("留在编辑") } }
    })
}
