package com.cfst.app

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cfst.app.documents.DocumentOperationOwner
import com.cfst.app.ui.components.DocumentOperationBanner

/** Independent safety path: no normal VM or automatic network request. */
@Composable fun RecoveryScreen(message:String,onRestore:()->Unit,onCancel:()->Unit) {
    val app=LocalContext.current.applicationContext as Application
    val owner=remember { DocumentOperationOwner.get(app) }; val operation by owner.state.collectAsState()
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("状态恢复",style=MaterialTheme.typography.headlineMedium)
        Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium) { Text(message.take(2048),Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onErrorContainer) }
        Text("不清除数据、不卸载APP。有效备份完全校验后，仍需点击确认；原损坏来源保留在私有恢复目录。")
        DocumentOperationBanner(owner,onCancel,{})
        Button(onClick=onRestore,enabled=!owner.blocksStart(),modifier=Modifier.fillMaxWidth()) { Text("选择有效状态备份") }
        if(operation?.stage==DocumentOperationOwner.Stage.PREVIEW) Text("请在确认对话框决定是否恢复，不会自动应用。")
    }
}
