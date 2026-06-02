package com.akhavanskii.aichallenge.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF0B6B57),
        onPrimary = Color.White,
        secondary = Color(0xFF4D5F7A),
        tertiary = Color(0xFF7B4B5F),
        background = Color(0xFFFAFCFB),
        surface = Color(0xFFFAFCFB),
        surfaceVariant = Color(0xFFE0E7E3),
        error = Color(0xFFB3261E),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF63D7BA),
        onPrimary = Color(0xFF00382C),
        secondary = Color(0xFFB5C7E4),
        tertiary = Color(0xFFE7B8CA),
        background = Color(0xFF101412),
        surface = Color(0xFF101412),
        surfaceVariant = Color(0xFF404944),
        error = Color(0xFFFFB4AB),
    )

@Composable
fun AIChallengeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColors
            else -> LightColors
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AIChallengeTypography,
        content = content,
    )
}
