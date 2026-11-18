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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.euromillones.app.data.Mask
import com.euromillones.app.data.MaskScope

val CellW = 30.dp
val CellH = 28.dp

/** Casilla de tabla con borde, estilo hoja de cálculo. */
@Composable
fun TableCell(
    width: Dp,
    background: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
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

/** Casilla con un bloque de máscara en negrita, sin colores. Las estrellas llevan fondo gris suave. */
@Composable
fun TokenCell(token: String, isStars: Boolean, width: Dp = CellW) {
    TableCell(width, if (isStars) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent) {
        Text(
            token,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

/** Las casillas de una máscara (llamar dentro de un Row). */
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
fun MaskHeaderCells(scope: MaskScope, width: Dp = CellW, starWidth: Dp = width) {
    if (scope != MaskScope.ESTRELLAS) (1..5).forEach { HeaderCell("N$it", width) }
    if (scope != MaskScope.NUMEROS) HeaderCell("★", starWidth)
}

/** Tabla leyenda de los criterios. */
@Composable
fun MaskLegend(window: Int) {
    val w0 = 72.dp
    val w = 86.dp
    Column {
        Row {
            HeaderCell("", w0)
            HeaderCell("L", w)
            HeaderCell("M", w)
            HeaderCell("H", w)
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
        Spacer(Modifier.height(6.dp))
        Row {
            HeaderCell("Últ. $window", w0)
            HeaderCell("C caliente", w)
            HeaderCell("T templado", w)
            HeaderCell("F frío", w)
        }
        Row {
            TextCell("Números", w0, bold = true)
            TextCell("16 que más", w)
            TextCell("18 del medio", w)
            TextCell("16 que menos", w)
        }
        Row {
            TextCell("Estrellas", w0, bold = true)
            TextCell("4 que más", w)
            TextCell("4 del medio", w)
            TextCell("4 que menos", w)
        }
    }
}

/**
 * Bolas con su máscara debajo: decena+unidad y, si se pasa [tempOf], la letra C/T/F.
 */
@Composable
fun BallsWithLetters(
    numbers: List<Int>,
    stars: List<Int>,
    size: Dp = 36.dp,
    tempOf: ((Int) -> String)? = null,
    starTempOf: ((Int) -> String)? = null,
) {
    Row {
        numbers.sorted().forEach { n ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 4.dp)) {
                Ball(n, isStar = false, size = size)
                BoldLetters(Mask.token(n))
                tempOf?.let { BoldLetters(it(n)) }
            }
        }
        Spacer(Modifier.width(4.dp))
        stars.sorted().forEach { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 4.dp)) {
                Ball(s, isStar = true, size = size)
                BoldLetters(Mask.star(s).name)
                starTempOf?.let { BoldLetters(it(s)) }
            }
        }
    }
}

@Composable
private fun BoldLetters(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
}
