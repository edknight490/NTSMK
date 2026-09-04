package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = EInkWhite,
    onPrimary = EInkBlack,
    secondary = EInkWhite,
    onSecondary = EInkBlack,
    tertiary = EInkMediumGray,
    background = EInkBlack,
    onBackground = EInkWhite,
    surface = EInkBlack,
    onSurface = EInkWhite,
    surfaceVariant = EInkDarkGray,
    onSurfaceVariant = EInkWhite,
    outline = EInkWhite,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = EInkBlack,
    onPrimary = EInkWhite,
    secondary = EInkBlack,
    onSecondary = EInkWhite,
    tertiary = EInkMediumGray,
    background = EInkWhite,
    onBackground = EInkBlack,
    surface = EInkWhite,
    onSurface = EInkBlack,
    surfaceVariant = EInkLightGray,
    onSurfaceVariant = EInkBlack,
    outline = EInkBlack,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color disabled to ensure consistent Mudita monochrome experience
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
