package com.cfst.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cfst.app.documents.DocumentOperationOwner
import com.cfst.app.ui.presentation.documentPresentation

@Composable fun DocumentOperationBanner(owner:DocumentOperationOwner,onCancel:()->Unit,onRetry:()->Unit) {
    val op by owner.state.collectAsState()
    if(op==null || op?.stage in setOf(DocumentOperationOwner.Stage.IDLE,DocumentOperationOwner.Stage.DONE,DocumentOperationOwner.Stage.CANCELLED)) return
    val p=documentPresentation(owner)
    Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().testTag("document-banner")) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(p.label,style=MaterialTheme.typography.titleSmall)
            if(p.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(p.message.isNotEmpty()) Text(p.message,style=MaterialTheme.typography.bodySmall)
            if(p.canCancel) TextButton(onClick=onCancel,modifier=Modifier.testTag("document-cancel")) { Text("取消文档操作") }
            if(p.canRetry) TextButton(onClick=onRetry,modifier=Modifier.testTag("document-retry")) { Text("重新选择位置重试导出") }
        }
    }
}
