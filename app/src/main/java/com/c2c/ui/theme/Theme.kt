package com.c2c.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val CyberColorScheme = darkColorScheme(
    primary = CyberNeonCyan,
    secondary = CyberNeonPink,
    tertiary = CyberNeonYellow,
    background = CyberBlack,
    surface = CyberDarkGray,
    onPrimary = CyberBlack,
    onSecondary = CyberBlack,
    onBackground = CyberWhite,
    onSurface = CyberNeonCyan
)

val CyberShapes = Shapes(
    small = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
    medium = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
    large = CutCornerShape(topStart = 24.dp, bottomEnd = 24.dp)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = CyberBlack.toArgb()
            window.navigationBarColor = CyberBlack.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = CyberColorScheme,
        shapes = CyberShapes,
        typography = Typography(),
        content = content
    )
}