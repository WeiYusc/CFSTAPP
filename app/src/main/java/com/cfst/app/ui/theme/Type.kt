package com.cfst.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

// 在 M3 默认字体上做两处克制调整：速度/延迟等数字场景启用等宽数字（tnum），
// 标题与徽章加粗一档，强化信息层级；不引入外部字体。
private val Base=Typography()
val CfstTypography=Base.copy(
    displayLarge=Base.displayLarge.copy(fontFeatureSettings="tnum"),displayMedium=Base.displayMedium.copy(fontFeatureSettings="tnum"),displaySmall=Base.displaySmall.copy(fontFeatureSettings="tnum"),
    headlineLarge=Base.headlineLarge.copy(fontFeatureSettings="tnum"),headlineMedium=Base.headlineMedium.copy(fontFeatureSettings="tnum"),headlineSmall=Base.headlineSmall.copy(fontFeatureSettings="tnum"),
    titleLarge=Base.titleLarge.copy(fontWeight=FontWeight.SemiBold),titleMedium=Base.titleMedium.copy(fontWeight=FontWeight.SemiBold),
    labelLarge=Base.labelLarge.copy(fontWeight=FontWeight.SemiBold)
)
