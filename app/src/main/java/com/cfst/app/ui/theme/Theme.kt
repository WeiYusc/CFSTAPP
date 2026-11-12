package com.cfst.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// 完整 M3 色板：以品牌蓝 0xFF2563EB 为种子；primary/background/surface/surfaceVariant 与旧版保持一致，
// 未再让 primaryContainer 等槽位回落到 M3 默认紫色系。
private val LightColors=lightColorScheme(
    primary=Color(0xFF2563EB),onPrimary=Color(0xFFFFFFFF),primaryContainer=Color(0xFFD9E4FF),onPrimaryContainer=Color(0xFF0B2A80),inversePrimary=Color(0xFFAFC6FF),
    secondary=Color(0xFF565E71),onSecondary=Color(0xFFFFFFFF),secondaryContainer=Color(0xFFDBE2F9),onSecondaryContainer=Color(0xFF141B2C),
    tertiary=Color(0xFF00696C),onTertiary=Color(0xFFFFFFFF),tertiaryContainer=Color(0xFF9CF1F4),onTertiaryContainer=Color(0xFF002022),
    background=Color(0xFFF2F3F7),onBackground=Color(0xFF1D1F24),surface=Color(0xFFFFFFFF),onSurface=Color(0xFF1D1F24),
    surfaceVariant=Color(0xFFE8ECF4),onSurfaceVariant=Color(0xFF414754),outline=Color(0xFF727786),outlineVariant=Color(0xFFC3C8D6),
    inverseSurface=Color(0xFF30333B),inverseOnSurface=Color(0xFFEFF0F6),scrim=Color(0xFF000000),
    error=Color(0xFFB3261E),onError=Color(0xFFFFFFFF),errorContainer=Color(0xFFF9DEDC),onErrorContainer=Color(0xFF410E0B)
)
private val DarkColors=darkColorScheme(
    primary=Color(0xFFAFC6FF),onPrimary=Color(0xFF002B73),primaryContainer=Color(0xFF1D4297),onPrimaryContainer=Color(0xFFD8E2FF),inversePrimary=Color(0xFF2563EB),
    secondary=Color(0xFFBFC6DC),onSecondary=Color(0xFF293042),secondaryContainer=Color(0xFF3F4759),onSecondaryContainer=Color(0xFFDBE2F9),
    tertiary=Color(0xFF80D4E2),onTertiary=Color(0xFF003639),tertiaryContainer=Color(0xFF004F52),onTertiaryContainer=Color(0xFF9CF1F4),
    background=Color(0xFF111216),onBackground=Color(0xFFE5E7ED),surface=Color(0xFF1D1F24),onSurface=Color(0xFFE5E7ED),
    surfaceVariant=Color(0xFF30343D),onSurfaceVariant=Color(0xFFC5CBD6),outline=Color(0xFF8F95A3),outlineVariant=Color(0xFF444A58),
    inverseSurface=Color(0xFFE5E7ED),inverseOnSurface=Color(0xFF30333B),scrim=Color(0xFF000000),
    error=Color(0xFFFFB4AB),onError=Color(0xFF690005),errorContainer=Color(0xFF93000A),onErrorContainer=Color(0xFFFFDAD6)
)

/** 结果三档之外补充的语义色：优选=success、短样本=warning；异常仍走主题 error。 */
@Immutable class ExtendedColors(val success:Color,val onSuccess:Color,val successContainer:Color,val onSuccessContainer:Color,val warning:Color,val onWarning:Color,val warningContainer:Color,val onWarningContainer:Color)
private val LightExtended=ExtendedColors(Color(0xFF176D2F),Color(0xFFFFFFFF),Color(0xFFB5F1B8),Color(0xFF002109),Color(0xFF815512),Color(0xFFFFFFFF),Color(0xFFFFDDB0),Color(0xFF281800))
private val DarkExtended=ExtendedColors(Color(0xFF9AD49D),Color(0xFF003911),Color(0xFF005320),Color(0xFFB5F1B8),Color(0xFFFFB94F),Color(0xFF432C00),Color(0xFF5F4200),Color(0xFFFFDDB0))
val LocalExtendedColors=staticCompositionLocalOf { LightExtended }

/** 分类 → (容器色, 内容色)：优选/短样本/仅延迟/异常 各有归属。 */
@Composable fun bucketColors(bucket:String):Pair<Color,Color> { val e=LocalExtendedColors.current; return when(bucket) {
    "优选"->e.successContainer to e.onSuccessContainer
    "短样本"->e.warningContainer to e.onWarningContainer
    "仅延迟"->MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    else->MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
} }

@Composable fun CfstTheme(dark:Boolean=isSystemInDarkTheme(),content:@Composable ()->Unit) {
    val colors=if(dark) DarkColors else LightColors
    val extended=if(dark) DarkExtended else LightExtended
    val view=LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window,view).apply { isAppearanceLightStatusBars=!dark; isAppearanceLightNavigationBars=!dark }
        }
    }
    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(colorScheme=colors,typography=CfstTypography,shapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(20.dp)),content=content)
    }
}
