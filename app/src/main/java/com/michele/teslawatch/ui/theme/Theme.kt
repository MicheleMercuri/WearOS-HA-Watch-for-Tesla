package com.michele.teslawatch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

// Palette leggibile anche da chi non distingue rosso e verde: blu = attivo, arancione = da notare
object TeslaColors {
    val Active = Color(0xFF0A84FF)
    val ActiveDark = Color(0xFF0A4A8C)
    val Alert = Color(0xFFFF9F0A)
    val AlertDark = Color(0xFF8A5300)
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF1C1C1E)
    val SurfaceDim = Color(0xFF2C2C2E)
    val White = Color(0xFFFFFFFF)
    val Gray = Color(0xFF8E8E93)
    val GrayLight = Color(0xFFAEAEB2)
}

private val teslaColorPalette = Colors(
    primary = TeslaColors.Active,
    primaryVariant = TeslaColors.Active,
    secondary = TeslaColors.Alert,
    secondaryVariant = TeslaColors.Alert,
    background = TeslaColors.Black,
    surface = TeslaColors.Surface,
    error = TeslaColors.Alert,
    onPrimary = TeslaColors.White,
    onSecondary = TeslaColors.Black,
    onBackground = TeslaColors.White,
    onSurface = TeslaColors.White,
    onSurfaceVariant = TeslaColors.GrayLight,
    onError = TeslaColors.Black
)

@Composable
fun TeslaWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = teslaColorPalette,
        content = content
    )
}
