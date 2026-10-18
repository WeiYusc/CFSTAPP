package com.cfst.app.ui.lists

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.cfst.app.utils.IpRangeParser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.documents.DocumentOperationOwner

@Composable fun IpListScreen(vm:SpeedTestViewModel,onFile:()->Unit,onFinish:(Boolean)->Unit) {
    val content by vm.currentListContent.collectAsState(); val status by vm.statusMessage.collectAsState()
    val preview by vm.importPreview.collectAsState(); val source by vm.sourceLabel.collectAsState()
    val family by vm.useIpv6.collectAsState(); val running by vm.isTesting.collectAsState()
    val selected by vm.selectedSpecifiedIpSegments.collectAsState(); val segments by vm.availableIpSegments.collectAsState()
    val specified by vm.useSpecifiedIpSegments.collectAsState(); val pastePending by vm.pastePending.collectAsState(); val pasteText by vm.pasteText.collectAsState()
    val app=LocalContext.current.applicationContext as Application
    val owner=remember(app) { DocumentOperationOwner.get(app) }
    val operation by owner.state.collectAsState()
    val canMutate=operation.let { !running && !owner.blocksStart() }
    var text by remember(content) { mutableStateOf(content) }; var restore by remember { mutableStateOf(false) }
    var pasteExpanded by rememberSaveable { mutableStateOf(false) }; var help by rememberSaveable { mutableStateOf(false) }
    val displaySegments=segments.distinct()
    LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=16.dp)) {
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("当前来源",style=MaterialTheme.typography.titleMedium); Text("$source · ${if(family) "IPv6" else "IPv4"}")
            Text("${segments.size} 条目/网段行 · 展示 ${displaySegments.size} 项，不是 IP 总数")
            if(displaySegments.size!=segments.size) Text("重复条目只展示一次；原始来源内容不变",style=MaterialTheme.typography.bodySmall)
        } } }
        item { if(status.isNotBlank()) Text(status.take(2048),style=MaterialTheme.typography.bodySmall) }
        item { Button(onClick=onFile,enabled=canMutate,modifier=Modifier.fillMaxWidth()) { Text("文件导入") }
            TextButton(onClick={pasteExpanded=!pasteExpanded}) { Text(if(pasteExpanded || pastePending) "收起粘贴编辑" else "粘贴列表") }
            if(pasteExpanded || pastePending) {
                OutlinedTextField(value=pasteText ?: text,onValueChange={text=it;vm.editPaste(it)},label={Text("literal / CIDR 每行一项")},modifier=Modifier.fillMaxWidth().testTag("paste-input"),enabled=canMutate,minLines=3,maxLines=8)
                Button(onClick={vm.previewPaste(pasteText ?: text)},enabled=canMutate) { Text("粘贴预览") }
            }
            if(pastePending) TextButton(onClick={vm.cancelImport();text=content}) { Text("取消粘贴编辑") }
            if(!canMutate) Text(if(running) "运行中的来源快照已冻结，停止后可编辑" else "文档操作尚未完成；请使用上方操作状态处理",style=MaterialTheme.typography.bodySmall)
        }
        preview?.let { p -> item { Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(16.dp)) {
            Text("将替换当前来源",style=MaterialTheme.typography.titleMedium)
            Text("有效条目/网段 ${p.entries} · IPv4 ${p.ipv4} / IPv6 ${p.ipv6}")
            Button(onClick={if(vm.confirmImport()) onFinish(true)},enabled=!running) { Text("确认应用") }
            TextButton(onClick={vm.cancelImport();onFinish(false)}) { Text("取消导入") }
        } } } }
        item { TextButton(onClick={help=!help}) { Text("格式与限制说明") }
            if(help) Text("上限1 MiB / 10,000物理行 / 每行4096 bytes；严格UTF-8。预览是条目/网段行数，不是CIDR内IP数量。混合文件保留来源内容，运行只使用当前family。")
        }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("来源更新",style=MaterialTheme.typography.titleMedium)
            TextButton(onClick=vm::fetchCloudflareIpRanges,enabled=canMutate) { Text("更新 Cloudflare 列表") }
            TextButton(onClick=vm::fetchGithubIpRanges,enabled=canMutate) { Text("更新 GitHub 列表") }
            OutlinedButton(onClick={restore=true},enabled=canMutate) { Text("恢复内置列表") }
        } } }
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("使用 IPv6"); Switch(checked=family,onCheckedChange=vm::updateUseIpv6,enabled=canMutate) } }
        item { Text("网段筛选 · 已选 ${selected.size} 项",style=MaterialTheme.typography.titleMedium)
            Row { Text("仅测试选中网段",Modifier.weight(1f)); Switch(specified,{vm.updateUseSpecifiedIpSegments(it)},enabled=canMutate && selected.isNotEmpty()) }
            Row { TextButton(onClick=vm::selectAllSpecifiedIpSegments,enabled=canMutate) { Text("全选当前 family") }; TextButton(onClick=vm::clearSpecifiedIpSegments,enabled=canMutate) { Text("清空选择") } }
        }
        items(displaySegments,key={it}) { segment ->
            val currentFamily=IpRangeParser.parseCidr(segment)?.isIpv6==family
            Row(Modifier.fillMaxWidth().toggleable(value=segment in selected,enabled=canMutate && currentFamily,role=Role.Checkbox,onValueChange={vm.toggleSpecifiedIpSegment(segment)}).padding(vertical=8.dp)) {
                Checkbox(segment in selected,null,enabled=canMutate && currentFamily)
                Column(Modifier.weight(1f)) { Text(segment); if(!currentFamily) Text("非当前 family，不可选择",style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if(restore) AlertDialog(onDismissRequest={restore=false},title={Text("恢复内置？")},text={Text("将原子替换当前列表与选择。")},confirmButton={TextButton(onClick={if(canMutate) vm.restoreBuiltIn();restore=false}) { Text("确认恢复") }},dismissButton={TextButton(onClick={restore=false}) { Text("取消") }})
}
