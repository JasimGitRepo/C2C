package com.c2c.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

// The Hacker Aesthetic CRT Modifier
fun Modifier.crtTerminalEffect() = this.drawWithContent {
    drawContent()
    // Scanlines
    for (i in 0 until size.height.toInt() step 6) {
        drawLine(
            color = Color.Black.copy(alpha = 0.25f),
            start = Offset(0f, i.toFloat()),
            end = Offset(size.width, i.toFloat()),
            strokeWidth = 2f
        )
    }
    // Terminal Vignette Glow
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0x66001A1A)),
            radius = size.width
        )
    )
}

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