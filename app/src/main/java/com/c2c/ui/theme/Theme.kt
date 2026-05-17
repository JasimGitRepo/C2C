package com.c2c.ui.theme

import android.app.Activity
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val PremiumColorScheme = darkColorScheme(
    primary = ActionBlue,
    secondary = PremiumPurple,
    background = SoulBackground,
    surface = GlassSurface,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

val PremiumShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun AnimatedMeshBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "mesh")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(25000, easing = LinearEasing)),
        label = "angle"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(color = SoulBackground)

        val width = size.width
        val height = size.height
        val radius = width * 0.8f

        // Purple Blob
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(PremiumPurple.copy(alpha = 0.4f), Color.Transparent),
                radius = radius
            ),
            radius = radius,
            center = Offset(
                x = width / 2 + (Math.cos(Math.toRadians(angle.toDouble())).toFloat() * width / 3),
                y = height / 3 + (Math.sin(Math.toRadians(angle.toDouble())).toFloat() * height / 4)
            )
        )

        // Teal Blob
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(PremiumTeal.copy(alpha = 0.3f), Color.Transparent),
                radius = radius
            ),
            radius = radius,
            center = Offset(
                x = width / 2 + (Math.cos(Math.toRadians((angle + 120).toDouble())).toFloat() * width / 3),
                y = height / 1.5f + (Math.sin(Math.toRadians((angle + 120).toDouble())).toFloat() * height / 4)
            )
        )

        // Rose Blob
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(PremiumRose.copy(alpha = 0.25f), Color.Transparent),
                radius = radius
            ),
            radius = radius,
            center = Offset(
                x = width / 2 + (Math.cos(Math.toRadians((angle + 240).toDouble())).toFloat() * width / 3),
                y = height / 2 + (Math.sin(Math.toRadians((angle + 240).toDouble())).toFloat() * height / 4)
            )
        )
    }
}

@Composable
fun PremiumTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = SoulBackground.toArgb()
            window.navigationBarColor = SoulBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = PremiumColorScheme,
        shapes = PremiumShapes,
        typography = Typography(), // Using elegant system default sans-serif
        content = {
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedMeshBackground()
                content()
            }
        }
    )
}