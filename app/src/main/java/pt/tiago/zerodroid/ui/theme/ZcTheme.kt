package pt.tiago.zerodroid.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** Token access from any composable. */
val LocalZc = staticCompositionLocalOf { ZcTokens }

/** Midnight ColorScheme mapped from ZcTokens: dark only, no dynamic color. */
private val ZcColorScheme: ColorScheme = darkColorScheme(
    primary = ZcTokens.accentStart,
    onPrimary = ZcTokens.onAccent,
    primaryContainer = ZcTokens.raised,
    onPrimaryContainer = ZcTokens.text,
    secondary = ZcTokens.accentEnd,
    onSecondary = ZcTokens.onAccent,
    tertiary = ZcTokens.attention,
    onTertiary = ZcTokens.background,
    background = ZcTokens.background,
    onBackground = ZcTokens.text,
    surface = ZcTokens.surface,
    onSurface = ZcTokens.text,
    surfaceVariant = ZcTokens.raised,
    onSurfaceVariant = ZcTokens.muted,
    surfaceContainer = ZcTokens.surface,
    surfaceContainerHigh = ZcTokens.raised,
    surfaceContainerHighest = ZcTokens.raised,
    surfaceContainerLow = ZcTokens.surface,
    error = ZcTokens.danger,
    onError = ZcTokens.background,
    outline = ZcTokens.hairlineStrong,
    outlineVariant = ZcTokens.hairline,
    scrim = ZcTokens.scrim,
)

private val Selection = TextSelectionColors(
    handleColor = ZcTokens.accentStart,
    backgroundColor = ZcTokens.accentStart.copy(alpha = 0.3f),
)

@Composable
fun ZcTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZcColorScheme) {
        CompositionLocalProvider(LocalZc provides ZcTokens, LocalTextSelectionColors provides Selection) {
            ProvideTextStyle(ZcType.body, content)
        }
    }
}
