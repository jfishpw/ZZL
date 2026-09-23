package com.zzl.guardian.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Purple = Color(0xFF534AB7)
private val PurpleLight = Color(0xFF7F77DD)
private val PurpleContainer = Color(0xFFEEEDFE)
private val PurpleOnContainer = Color(0xFF26215C)
private val Teal = Color(0xFF1D9E75)
private val TealContainer = Color(0xFFE1F5EE)
private val Coral = Color(0xFFD85A30)

private val LightColors = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = PurpleContainer,
    onPrimaryContainer = PurpleOnContainer,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = TealContainer,
    onSecondaryContainer = Color(0xFF04342C),
    error = Coral,
    onError = Color.White,
    background = Color(0xFFF7F6FC),
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFEEECF4),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCBC4CF),
)

private val DarkColors = darkColorScheme(
    primary = PurpleLight,
    onPrimary = Color(0xFF1F1A4D),
    primaryContainer = Color(0xFF3B3479),
    onPrimaryContainer = Color(0xFFCECBF6),
    secondary = Color(0xFF5DCAA5),
    onSecondary = Color(0xFF04342C),
    secondaryContainer = Color(0xFF0F6E56),
    onSecondaryContainer = Color(0xFF9FE1CB),
    error = Color(0xFFF09595),
    onError = Color(0xFF501313),
    background = Color(0xFF141318),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF1D1B21),
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF2A2731),
    onSurfaceVariant = Color(0xFFCAC4CF),
    outline = Color(0xFF948F99),
    outlineVariant = Color(0xFF49454F),
)

@Composable
fun ZzlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
