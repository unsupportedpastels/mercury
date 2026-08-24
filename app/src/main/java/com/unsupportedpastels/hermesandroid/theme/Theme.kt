package com.unsupportedpastels.hermesandroid.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

@Immutable
data class HermesSemanticColors(
    val active: Color,
    val onActive: Color,
    val completed: Color,
    val onCompleted: Color,
)

/** Semantic roles that are not represented by Material's standard status roles. */
val LocalHermesSemanticColors = staticCompositionLocalOf {
    HermesSemanticColors(
        active = Color(0xFFC68A16),
        onActive = Color(0xFF241A00),
        completed = Color(0xFF2D6A43),
        onCompleted = Color.White,
    )
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6969),
    onPrimary = Color(0xFFE0FFFE),
    primaryContainer = Color(0xFFA8EFEE),
    onPrimaryContainer = Color(0xFF005C5C),
    secondary = Color(0xFF4A6463),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E7),
    onSecondaryContainer = Color(0xFF3D5656),
    tertiary = Color(0xFF765A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDF92),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFFAFCFB),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFAFCFB),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceDim = Color(0xFFDAE0DD),
    surfaceBright = Color(0xFFFAFCFB),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F7F5),
    surfaceContainer = Color(0xFFECF2EF),
    surfaceContainerHigh = Color(0xFFE6ECE9),
    surfaceContainerHighest = Color(0xFFE0E5E2),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
    inverseSurface = Color(0xFF2D3130),
    inverseOnSurface = Color(0xFFEFF1EF),
    inversePrimary = Color(0xFF9BD0CF),
    scrim = Color.Black,
)

// AMOLED dark palette: pure-black canvases with neutral gray surfaces,
// ChatGPT-style restrained chrome. Teal accents reseeded from the canonical
// Hermes Teal (LENS_0 #041C1C, hue 196.8°) via the Material 3 tonal palette;
// neutrals stay gray (green-tinted grays removed, contrast raised).
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BD0CF),
    onPrimary = Color(0xFF0C4848),
    primaryContainer = Color(0xFF255A5A),
    onPrimaryContainer = Color(0xFFB7EDEC),
    secondary = Color(0xFFC5C5C5),
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = Color(0xFF27403F),
    onSecondaryContainer = Color(0xFFA9C5C4),
    tertiary = Color(0xFFF2C64D),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5B4400),
    onTertiaryContainer = Color(0xFFFFDF92),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF4F4F4),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF4F4F4),
    surfaceVariant = Color(0xFF2E2E2E),
    onSurfaceVariant = Color(0xFFC5C5C5),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF262626),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF1C1C1C),
    surfaceContainerHigh = Color(0xFF232323),
    surfaceContainerHighest = Color(0xFF2B2B2B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFF3A3A3A),
    inverseSurface = Color(0xFFE8E8E8),
    inverseOnSurface = Color(0xFF1C1C1C),
    inversePrimary = Color(0xFF336767),
    scrim = Color.Black,
)

private val LightSemanticColors = HermesSemanticColors(
    active = Color(0xFFC68A16),
    onActive = Color(0xFF241A00),
    completed = Color(0xFF2D6A43),
    onCompleted = Color.White,
)

private val DarkSemanticColors = HermesSemanticColors(
    active = Color(0xFFF2C64D),
    onActive = Color(0xFF241A00),
    completed = Color(0xFF8ED6A5),
    onCompleted = Color(0xFF0C3A1E),
)

@Composable
fun HermesAndroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors
    CompositionLocalProvider(LocalHermesSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colors,
            content = content,
        )
    }
}
