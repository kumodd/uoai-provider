package com.uoai.provider.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class StatusColors(
    val ready: Color,
    val readyContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val neutral: Color,
    val neutralContainer: Color,
)

private val LightStatusColors = StatusColors(
    ready = Color(0xFF059669),
    readyContainer = Color(0xFFD1FAE5),
    warning = Color(0xFFD97706),
    warningContainer = Color(0xFFFEF3C7),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2),
    neutral = Color(0xFF64748B),
    neutralContainer = Color(0xFFE2E8F0),
)

private val DarkStatusColors = StatusColors(
    ready = Color(0xFF34D399),
    readyContainer = Color(0xFF064E3B),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF451A03),
    error = Color(0xFFFB7185),
    errorContainer = Color(0xFF4C0519),
    neutral = Color(0xFF94A3B8),
    neutralContainer = Color(0xFF1E293B),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF115E59),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFA78BFA),
    background = Color(0xFF0B101B),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF121A2B),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1A2438),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF090D16),
    surfaceContainerLow = Color(0xFF121A2B),
    surfaceContainer = Color(0xFF172136),
    surfaceContainerHigh = Color(0xFF1E2B44),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
    error = Color(0xFFFB7185),
    onError = Color(0xFF4C0519),
    errorContainer = Color(0xFF4C0519),
    onErrorContainer = Color(0xFFFFE4E6),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = Color(0xFF0D9488),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF115E59),
    tertiary = Color(0xFF7C3AED),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEAF0F6),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFDDE4EE),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val ProviderShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

private val ProviderTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
)

@Composable
fun ProviderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColorScheme else LightColorScheme
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors
    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colors,
            shapes = ProviderShapes,
            typography = ProviderTypography,
            content = content,
        )
    }
}

object ProviderThemeDefaults {
    val statusColors: StatusColors
        @Composable get() = LocalStatusColors.current
}

