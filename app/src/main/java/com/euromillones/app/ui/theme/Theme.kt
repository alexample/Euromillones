package com.euromillones.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val EuroBlue = Color(0xFF1E3A8A)
val EuroGold = Color(0xFFF5B800)
val HitGreen = Color(0xFF16A34A)

private val Light = lightColorScheme(
    primary = EuroBlue,
    onPrimary = Color.White,
    secondary = EuroGold,
    onSecondary = Color.Black,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF3B5BC9),
    onPrimary = Color.White,
    secondary = EuroGold,
    onSecondary = Color.Black,
)

@Composable
fun EuroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
