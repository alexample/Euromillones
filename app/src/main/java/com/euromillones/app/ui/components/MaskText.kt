package com.euromillones.app.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.euromillones.app.data.Level
import com.euromillones.app.ui.theme.EuroGold

val LevelL = Color(0xFF0EA5E9)
val LevelM = Color(0xFF8B5CF6)
val LevelH = Color(0xFFEF4444)

fun Level.color(): Color = when (this) {
    Level.L -> LevelL
    Level.M -> LevelM
    Level.H -> LevelH
}

private fun letterColor(c: Char): Color = when (c) {
    'L' -> LevelL
    'M' -> LevelM
    'H' -> LevelH
    else -> Color.Unspecified
}

/**
 * Pinta una máscara tipo "HH ML LL HM HH LL" con cada letra de su color.
 * [starsTokenIndex]: posición del bloque de estrellas (se marca con fondo dorado), o -1 si no hay.
 */
@Composable
fun MaskText(
    mask: String,
    modifier: Modifier = Modifier,
    starsTokenIndex: Int = -1,
    style: TextStyle = LocalTextStyle.current,
) {
    val tokens = mask.split(" ")
    val text = buildAnnotatedString {
        tokens.forEachIndexed { i, t ->
            if (i > 0) append(" ")
            val isStars = i == starsTokenIndex || (starsTokenIndex == -2 && i == tokens.lastIndex)
            if (isStars) {
                withStyle(SpanStyle(background = EuroGold.copy(alpha = 0.35f))) {
                    t.forEach { c -> withStyle(SpanStyle(color = letterColor(c))) { append(c) } }
                }
            } else {
                t.forEach { c -> withStyle(SpanStyle(color = letterColor(c))) { append(c) } }
            }
        }
    }
    Text(text, modifier = modifier, style = style, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
}

/** Índice del bloque de estrellas según el tipo de máscara. */
const val STARS_LAST = -2
