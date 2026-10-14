package ua.monosync.ui.theme

import ua.monosync.R

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightFallback = lightColorScheme(
    primary = Color(0xFF4C662B), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEDA3), onPrimaryContainer = Color(0xFF354E16),
    secondary = Color(0xFF586249), secondaryContainer = Color(0xFFDCE7C8), onSecondaryContainer = Color(0xFF404A33),
    tertiary = Color(0xFF386663), tertiaryContainer = Color(0xFFBCECE7), onTertiaryContainer = Color(0xFF1F4E4B),
    background = Color(0xFFF9FAEF), surface = Color(0xFFF9FAEF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F4E9), surfaceContainer = Color(0xFFEEEFE3),
    surfaceContainerHigh = Color(0xFFE8E9DE), surfaceContainerHighest = Color(0xFFE2E3D8),
    onSurface = Color(0xFF1A1C16), onSurfaceVariant = Color(0xFF44483D), outline = Color(0xFF75796C), outlineVariant = Color(0xFFC5C8BA),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFB1D18A), onPrimary = Color(0xFF1F3701),
    primaryContainer = Color(0xFF354E16), onPrimaryContainer = Color(0xFFCDEDA3),
    secondary = Color(0xFFBFCBAD), secondaryContainer = Color(0xFF404A33), onSecondaryContainer = Color(0xFFDCE7C8),
    tertiary = Color(0xFFA0D0CB), tertiaryContainer = Color(0xFF1F4E4B), onTertiaryContainer = Color(0xFFBCECE7),
    background = Color(0xFF12140E), surface = Color(0xFF12140E),
    surfaceContainerLowest = Color(0xFF0C0F09), surfaceContainerLow = Color(0xFF1A1C16), surfaceContainer = Color(0xFF1E201A),
    surfaceContainerHigh = Color(0xFF282B24), surfaceContainerHighest = Color(0xFF33362E),
    onSurface = Color(0xFFE2E3D8), onSurfaceVariant = Color(0xFFC5C8BA), outline = Color(0xFF8F9285), outlineVariant = Color(0xFF44483D),
)

@Immutable
data class MoneyColors(val income: Color, val expense: Color, val transfer: Color, val warning: Color)

val LocalMoneyColors = staticCompositionLocalOf {
    MoneyColors(Color(0xFF2E6B3A), Color(0xFF1A1C16), Color(0xFF415F91), Color(0xFFBA1A1A))
}

private val base = Typography()

private val AppTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

val Numbers = TextStyle(fontFeatureSettings = "tnum")

/** Rounded heavy face close to the mono wordmark (M PLUS Rounded 1c Black, OFL). */
val MonoRounded = FontFamily(Font(R.font.mono_rounded, FontWeight.Black))

@Composable
fun MonoSyncTheme(dynamic: Boolean = true, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    val money = if (dark) {
        MoneyColors(Color(0xFF8FD89A), scheme.onSurface, Color(0xFFAAC7FF), Color(0xFFFFB4AB))
    } else {
        MoneyColors(Color(0xFF2E6B3A), scheme.onSurface, Color(0xFF415F91), Color(0xFFBA1A1A))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalMoneyColors provides money) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
