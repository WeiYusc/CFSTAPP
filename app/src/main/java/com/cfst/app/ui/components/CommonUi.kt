package com.cfst.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cfst.app.ui.theme.bucketColors

/** 结果分类语义徽章：优选=success / 短样本=warning / 仅延迟=primary / 异常=error。 */
@Composable fun StatusPill(bucket:String,modifier:Modifier=Modifier) {
    val pair=bucketColors(bucket)
    Surface(color=pair.first,contentColor=pair.second,shape=MaterialTheme.shapes.extraSmall,modifier=modifier) {
        Text(bucket,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(horizontal=8.dp,vertical=3.dp))
    }
}

/** 非交互信息徽章：可选图标 + 文本，用于网络信息行与配置摘要。 */
@Composable fun InfoChip(text:String,icon:ImageVector?=null,modifier:Modifier=Modifier) {
    Surface(color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurfaceVariant,shape=MaterialTheme.shapes.extraSmall,modifier=modifier) {
        Row(Modifier.padding(horizontal=8.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            if(icon!=null) Icon(icon,null,modifier=Modifier.size(14.dp))
            Text(text,style=MaterialTheme.typography.labelMedium)
        }
    }
}

/** 统一空状态：图标 + 主文案 + 辅助说明。 */
@Composable fun EmptyState(icon:ImageVector,title:String,body:String?=null,modifier:Modifier=Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Icon(icon,null,modifier=Modifier.size(44.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.55f))
        Text(title,style=MaterialTheme.typography.titleMedium,textAlign=TextAlign.Center)
        if(body!=null) Text(body,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
    }
}

/** 迷你指标：上标签下数值，用于结果卡的关键数据行。 */
@Composable fun MetricStat(label:String,value:String,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(1.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
    }
}

/** 前三名圆形排名徽章。 */
@Composable fun RankBadge(rank:Int,modifier:Modifier=Modifier) {
    Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer,contentColor=MaterialTheme.colorScheme.onPrimaryContainer,modifier=modifier.size(26.dp)) {
        Box(contentAlignment=Alignment.Center) { Text("#${rank+1}",style=MaterialTheme.typography.labelMedium) }
    }
}
