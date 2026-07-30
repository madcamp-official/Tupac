package com.example.mobileguiagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF245B4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9EDE5),
    onPrimaryContainer = Color(0xFF123B30),
    secondary = Color(0xFF53665F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2ECE7),
    onSecondaryContainer = Color(0xFF253B33),
    background = Color(0xFFF6F7F3),
    onBackground = Color(0xFF1A1D1B),
    surface = Color(0xFFFCFDF9),
    onSurface = Color(0xFF1A1D1B),
    surfaceVariant = Color(0xFFE9ECE7),
    onSurfaceVariant = Color(0xFF5A625E),
    outline = Color(0xFFBFC6C1),
    outlineVariant = Color(0xFFDCE1DD),
    error = Color(0xFF9B403A),
    errorContainer = Color(0xFFFFDAD6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FD3BE),
    onPrimary = Color(0xFF073729),
    primaryContainer = Color(0xFF244E41),
    onPrimaryContainer = Color(0xFFD9EDE5),
    secondary = Color(0xFFB7CCC2),
    onSecondary = Color(0xFF25372F),
    secondaryContainer = Color(0xFF344A41),
    onSecondaryContainer = Color(0xFFDCE9E3),
    background = Color(0xFF111512),
    onBackground = Color(0xFFE4E8E4),
    surface = Color(0xFF171C18),
    onSurface = Color(0xFFE4E8E4),
    surfaceVariant = Color(0xFF262D29),
    onSurfaceVariant = Color(0xFFBBC4BF),
    outline = Color(0xFF87918C),
    outlineVariant = Color(0xFF39423D),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF71332E),
)

@Composable
fun MobileGUIAgentTheme(content: @Composable () -> Unit) {
    val baseTypography = MaterialTheme.typography
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = baseTypography.copy(
            headlineMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                letterSpacing = (-0.5).sp,
            ),
            titleLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                letterSpacing = (-0.2).sp,
            ),
            titleMedium = baseTypography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            labelMedium = baseTypography.labelMedium.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
            ),
            bodyLarge = baseTypography.bodyLarge.copy(lineHeight = 24.sp),
        ),
        content = content,
    )
}
