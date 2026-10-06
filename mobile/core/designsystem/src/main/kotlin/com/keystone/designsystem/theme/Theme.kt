package com.keystone.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Indigo40,
    onPrimary = Color.White,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    secondary = Teal40,
    onSecondary = Color.White,
    secondaryContainer = Teal90,
    onSecondaryContainer = Teal20,
    background = Neutral98,
    onBackground = Neutral10,
    surface = Neutral98,
    onSurface = Neutral10,
    surfaceVariant = Neutral94,
    onSurfaceVariant = NeutralVariant30,
    surfaceContainer = Neutral96,
    surfaceContainerHigh = Neutral94,
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,
    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Red10,
)

private val DarkColors = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo20,
    primaryContainer = Indigo30,
    onPrimaryContainer = Indigo90,
    secondary = Teal80,
    onSecondary = Teal20,
    secondaryContainer = Teal30,
    onSecondaryContainer = Teal90,
    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = Neutral17,
    onSurfaceVariant = NeutralVariant80,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant30,
    error = Red80,
    onError = Red10,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Red90,
)

/** Semantic colours Material 3 doesn't define, e.g. success/warning for status pills. */
@Immutable
data class KeystoneStatusColors(
    val success: Color,
    val warning: Color,
)

private val LightStatus = KeystoneStatusColors(success = Green40, warning = Amber40)
private val DarkStatus = KeystoneStatusColors(success = Green80, warning = Amber80)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/**
 * App-wide theme. Brand colours are used instead of Android 12 dynamic colour so the
 * product looks consistent on every device (a deliberate product decision).
 */
@Composable
fun KeystoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors: ColorScheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = colors,
            typography = KeystoneTypography,
            shapes = KeystoneShapes,
            content = content,
        )
    }
}

/** Convenience accessor: `KeystoneTheme.status.success`. */
object KeystoneTheme {
    val status: KeystoneStatusColors
        @Composable get() = LocalStatusColors.current
}
