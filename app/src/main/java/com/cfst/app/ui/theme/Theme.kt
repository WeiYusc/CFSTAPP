package com.cfst.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val Light=lightColorScheme(primary=Color(0xFF2563EB),onPrimary=Color.White,background=Color(0xFFF2F3F7),onBackground=Color(0xFF1D1F24),surface=Color.White,onSurface=Color(0xFF1D1F24),surfaceVariant=Color(0xFFE8ECF4),onSurfaceVariant=Color(0xFF414754),error=Color(0xFFB3261E))
private val Dark=darkColorScheme(primary=Color(0xFFAFC6FF),onPrimary=Color(0xFF002B73),background=Color(0xFF111216),onBackground=Color(0xFFE5E7ED),surface=Color(0xFF1D1F24),onSurface=Color(0xFFE5E7ED),surfaceVariant=Color(0xFF30343D),onSurfaceVariant=Color(0xFFC5CBD6),error=Color(0xFFFFB4AB))
@Composable fun CfstTheme(dark:Boolean=isSystemInDarkTheme(),content:@Composable ()->Unit) {
    val colors=if(dark) Dark else Light
    val view=LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            window.statusBarColor=colors.background.toArgb(); window.navigationBarColor=colors.surface.toArgb()
            WindowCompat.getInsetsController(window,view).apply { isAppearanceLightStatusBars=!dark; isAppearanceLightNavigationBars=!dark }
        }
    }
    MaterialTheme(colorScheme=colors,shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(20.dp)),content=content)
}
