package com.euromillones.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.euromillones.app.data.Level
import com.euromillones.app.data.Mask
import com.euromillones.app.data.MaskScope
import com.euromillones.app.ui.theme.EuroGold

val LevelL = Color(0xFF0284C7)
val LevelM = Color(0xFF7C3AED)
val LevelH = Color(0xFFDC2626)

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

val CellW = 30.dp
val CellH = 28.dp
private val StarBg = EuroGold.copy(alpha = 0.25f)

/** Casilla de tabla con borde, estilo hoja de cálculo. */
@Composable
fun TableCell(
    width: Dp,
    background: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .width(width)
            .height(CellH)
            .background(background)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun HeaderCell(text: String, width: Dp) {
    TableCell(width, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
fun TextCell(
    text: String,
    width: Dp,
    color: Color = Color.Unspecified,
    bold: Boolean = false,
    background: Color = Color.Transparent,
) {
    TableCell(width, background) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** Una casilla con un bloque de la máscara ("HM", o "LH" en estrellas) con cada letra de su color. */
@Composable
fun TokenCell(token: String, isStars: Boolean, width: Dp = CellW) {
    TableCell(width, if (isStars) StarBg else Color.Transparent) {
        Text(
            buildAnnotatedString { token.forEach { c -> withStyle(SpanStyle(color = letterColor(c))) { append(c) } } },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Las casillas de una máscara completa / de números / de estrellas (llamar dentro de un Row). */
@Composable
fun MaskCells(mask: String, scope: MaskScope, width: Dp = CellW) {
    val tokens = mask.split(" ")
    tokens.forEachIndexed { i, t ->
        val isStars = scope == MaskScope.ESTRELLAS || (scope == MaskScope.COMPLETA && i == tokens.lastIndex)
        TokenCell(t, isStars, width)
    }
}

/** Cabeceras N1…N5 y ★ según el tipo de máscara (llamar dentro de un Row). */
@Composable
fun MaskHeaderCells(scope: MaskScope, width: Dp = CellW) {
    if (scope != MaskScope.ESTRELLAS) (1..5).forEach { HeaderCell("N$it", width) }
    if (scope != MaskScope.NUMEROS) HeaderCell("★", width)
}

/** Tabla leyenda del criterio L/M/H. */
@Composable
fun MaskLegend() {
    val w0 = 78.dp
    val w = 74.dp
    Column {
        Row {
            HeaderCell("", w0)
            Level.entries.forEach { l ->
                TableCell(w, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                    Text(l.name, color = l.color(), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
        listOf(
            "Decena" to listOf("0-1 (1-19)", "2-3 (20-39)", "4-5 (40-50)"),
            "Unidad" to listOf("0-2", "3-6", "7-9"),
            "Estrella" to listOf("1-4", "5-8", "9-12"),
        ).forEach { (name, vals) ->
            Row {
                TextCell(name, w0, bold = true)
                vals.forEach { TextCell(it, w) }
            }
        }
    }
}

/** Bolas con las letras de la máscara debajo de cada una. */
@Composable
fun BallsWithLetters(numbers: List<Int>, stars: List<Int>, size: Dp = 36.dp) {
    Row {
        numbers.sorted().forEach { n ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 4.dp)) {
                Ball(n, isStar = false, size = size)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Mask.dec(n).color())) { append(Mask.dec(n).name) }
                        withStyle(SpanStyle(color = Mask.uni(n).color())) { append(Mask.uni(n).name) }
                    },
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        stars.sorted().forEach { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 4.dp)) {
                Ball(s, isStar = true, size = size)
                Text(
                    Mask.star(s).name,
                    color = Mask.star(s).color(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
