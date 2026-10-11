package dev.bezh.zamer.ui.theme

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import dev.bezh.zamer.data.ThemeMode

// Laser-red palette used when wallpaper colours are off or unavailable.
private val LightColors = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775651),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD5),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF6E5D0E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF9E287),
    onTertiaryContainer = Color(0xFF221B00),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF0EE),
    surfaceContainer = Color(0xFFFCEAE7),
    surfaceContainerHigh = Color(0xFFF7E4E1),
    surfaceContainerHighest = Color(0xFFF1DEDC),
    outline = Color(0xFF857370),
    outlineVariant = Color(0xFFD8C2BE),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFE7BDB6),
    onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFFDCC66E),
    onTertiary = Color(0xFF3B2F00),
    tertiaryContainer = Color(0xFF544600),
    onTertiaryContainer = Color(0xFFF9E287),
    background = Color(0xFF1A1110),
    onBackground = Color(0xFFF1DFDC),
    surface = Color(0xFF1A1110),
    onSurface = Color(0xFFF1DFDC),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BE),
    surfaceContainerLowest = Color(0xFF140C0B),
    surfaceContainerLow = Color(0xFF231918),
    surfaceContainer = Color(0xFF271D1C),
    surfaceContainerHigh = Color(0xFF322827),
    surfaceContainerHighest = Color(0xFF3D3231),
    outline = Color(0xFFA08C89),
    outlineVariant = Color(0xFF534341),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Digits of equal width so a changing reading doesn't jitter. */
val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

private val ZamerTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.merge(TabularFigures).copy(fontWeight = FontWeight.SemiBold),
        displayMedium = base.displayMedium.merge(TabularFigures).copy(fontWeight = FontWeight.SemiBold),
        displaySmall = base.displaySmall.merge(TabularFigures).copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.merge(TabularFigures),
        headlineMedium = base.headlineMedium.merge(TabularFigures),
        titleLarge = base.titleLarge.merge(TabularFigures),
        titleMedium = base.titleMedium.merge(TabularFigures),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ZamerTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Edge-to-edge picks bar icon colours from the system theme; follow the app's choice instead.
    val window = LocalActivity.current?.window
    val view = LocalView.current
    SideEffect {
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        typography = ZamerTypography,
        content = content,
    )
}
