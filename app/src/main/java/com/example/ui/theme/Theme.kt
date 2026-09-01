package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val JarvisDarkColorScheme = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = Color.Black,
    primaryContainer = JarvisCyanDark,
    onPrimaryContainer = Color.White,
    secondary = JarvisBlue,
    onSecondary = Color.White,
    secondaryContainer = JarvisSurfaceVariant,
    onSecondaryContainer = TextPrimary,
    tertiary = JarvisSpeakingBlue,
    background = JarvisDeepBackground,
    onBackground = TextPrimary,
    surface = JarvisSurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = JarvisSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = JarvisBorderGlow,
    error = JarvisErrorRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = JarvisDarkColorScheme,
        typography = Typography,
        content = content
    )
}
