package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

enum class ThemePreference {
    DARK, LIGHT, SYSTEM
}

private val SignalDarkColorScheme = darkColorScheme(
    primary = SignalEmerald,
    onPrimary = Color(0xFF032319),
    primaryContainer = SignalEmeraldContainer,
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = ElectricCyan,
    onSecondary = Color(0xFF04222A),
    secondaryContainer = ElectricCyanContainer,
    onSecondaryContainer = Color(0xFFA5F3FC),
    tertiary = WarmAmber,
    onTertiary = Color(0xFF2B1700),
    tertiaryContainer = WarmAmberContainer,
    onTertiaryContainer = Color(0xFFFDE68A),
    error = CoralRed,
    onError = Color.White,
    errorContainer = CoralRedContainer,
    onErrorContainer = Color(0xFFFECACA),
    background = ObsidianBackground,
    onBackground = TextPrimaryDark,
    surface = CarbonSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ElevatedCarbon,
    onSurfaceVariant = TextSecondaryDark,
    outline = SubtleBorderDark,
    outlineVariant = Color(0xFF1E293B)
)

private val SignalLightColorScheme = lightColorScheme(
    primary = SignalEmeraldDark,
    onPrimary = Color.White,
    primaryContainer = LightEmeraldContainer,
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    secondaryContainer = LightCyanContainer,
    onSecondaryContainer = Color(0xFF155E75),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    tertiaryContainer = LightAmberContainer,
    onTertiaryContainer = Color(0xFF78350F),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = LightRedContainer,
    onErrorContainer = Color(0xFF7F1D1D),
    background = MistBackground,
    onBackground = TextPrimaryLight,
    surface = CrispSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = ElevatedMist,
    onSurfaceVariant = TextSecondaryLight,
    outline = SubtleBorderLight,
    outlineVariant = Color(0xFFE2E8F0)
)

val OfflineChatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun OfflineChatTheme(
    themePreference: ThemePreference = ThemePreference.DARK,
    content: @Composable () -> Unit
) {
    val isDark = when (themePreference) {
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (isDark) SignalDarkColorScheme else SignalLightColorScheme
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    MaterialTheme(
        colorScheme = colorScheme,
        typography = offlineChatTypography(isRtl),
        shapes = OfflineChatShapes,
        content = content
    )
}
