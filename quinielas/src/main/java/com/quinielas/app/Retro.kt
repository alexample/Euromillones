package com.quinielas.app

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * ESTILO QUINIELA DE LOS 80: boleto de papel color crema, tinta roja de imprenta, letra de máquina de escribir
 * y las cruces hechas a bolígrafo azul.
 */
val Papel = Color(0xFFF3E9D2)
val PapelOscuro = Color(0xFFE8DABA)
val Tinta = Color(0xFFB3202A)          // rojo de imprenta del boleto
val TintaSuave = Color(0xFFF2C9C4)
val Boli = Color(0xFF1F3A93)           // bolígrafo azul
val Texto = Color(0xFF2B2118)

/** Letra de máquina de escribir (la que trae Android; si no está, monoespaciada). */
val Maquina: FontFamily = runCatching {
    val tf = Typeface.create("cutive-mono", Typeface.NORMAL)
    if (tf == Typeface.DEFAULT) FontFamily.Monospace else FontFamily(tf)
}.getOrDefault(FontFamily.Monospace)

@Composable
fun TemaRetro(content: @Composable () -> Unit) {
    val colores = lightColorScheme(
        primary = Tinta, onPrimary = Papel, primaryContainer = TintaSuave, onPrimaryContainer = Texto,
        secondary = Boli, secondaryContainer = TintaSuave, onSecondaryContainer = Texto,
        background = Papel, onBackground = Texto, surface = Papel, onSurface = Texto,
        surfaceVariant = PapelOscuro, onSurfaceVariant = Texto, outline = Tinta,
        surfaceContainer = PapelOscuro, surfaceContainerHigh = PapelOscuro, surfaceContainerHighest = PapelOscuro,
        surfaceContainerLow = Papel, surfaceContainerLowest = Papel,
    )
    val t = Typography()
    fun TextStyle.m() = copy(fontFamily = Maquina)
    val tipos = Typography(
        displayLarge = t.displayLarge.m(), displayMedium = t.displayMedium.m(), displaySmall = t.displaySmall.m(),
        headlineLarge = t.headlineLarge.m(), headlineMedium = t.headlineMedium.m(), headlineSmall = t.headlineSmall.m(),
        titleLarge = t.titleLarge.m(), titleMedium = t.titleMedium.m(), titleSmall = t.titleSmall.m(),
        bodyLarge = t.bodyLarge.m(), bodyMedium = t.bodyMedium.m(), bodySmall = t.bodySmall.m(),
        labelLarge = t.labelLarge.m(), labelMedium = t.labelMedium.m(), labelSmall = t.labelSmall.m(),
    )
    MaterialTheme(colorScheme = colores, typography = tipos, content = content)
}

/** Casilla del boleto: [1] [X] [2]; la marcada lleva una cruz de bolígrafo. */
@Composable
private fun Casilla(signo: Char, marcada: Boolean) {
    Box(Modifier.size(width = 11.dp, height = 13.dp).border(0.6.dp, Tinta), contentAlignment = Alignment.Center) {
        if (marcada) Text("X", color = Boli, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.rotate(-8f))
        else Text(signo.toString(), color = Tinta.copy(alpha = 0.55f), fontSize = 7.sp, fontFamily = Maquina)
    }
}

/**
 * Un boleto como los de antes: 14 partidos (más el pleno) en filas y hasta 8 columnas (apuestas) por boleto,
 * cada una con sus casillas 1 X 2.
 */
@Composable
fun Boleto(numero: Int, primera: Int, partidos: List<PartidoProx>, columnas: List<Int>, jornada: Int?) {
    Column(
        Modifier.fillMaxWidth().background(Papel, RoundedCornerShape(2.dp)).border(1.5.dp, Tinta, RoundedCornerShape(2.dp)).padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("QUINIELA", color = Tinta, fontFamily = Maquina, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 3.sp)
            Spacer(Modifier.width(8.dp))
            Text("JORNADA ${jornada ?: "—"}", color = Tinta, fontFamily = Maquina, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("BOLETO Nº %03d".format(numero), color = Tinta, fontFamily = Maquina, fontSize = 11.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tinta))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Spacer(Modifier.height(14.dp))
                for (i in 0 until 14) {
                    val p = partidos.getOrNull(i)
                    Row(Modifier.height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("%2d".format(i + 1), color = Tinta, fontFamily = Maquina, fontSize = 10.sp, modifier = Modifier.width(16.dp))
                        Text(
                            p?.let { "${it.local.uppercase().take(10)}-${it.visit.uppercase().take(10)}" } ?: "",
                            color = Texto, fontFamily = Maquina, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Clip,
                            modifier = Modifier.width(118.dp),
                        )
                    }
                }
            }
            columnas.forEachIndexed { k, col ->
                val t = Generador.texto(col)
                Column(Modifier.padding(start = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${primera + k}", color = Tinta, fontFamily = Maquina, fontSize = 9.sp, modifier = Modifier.height(14.dp))
                    for (i in 0 until 14) {
                        Row(Modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (s in SIGNOS) Casilla(s, t[i] == s)
                        }
                    }
                    Text(Mascara.de(t).toString(), color = Boli, fontFamily = Maquina, fontSize = 8.sp)
                }
            }
        }
        Text("PRECIO DE LA APUESTA: ${"%.2f".format(Generador.PRECIO)} €  ·  ${columnas.size} APUESTAS", color = Tinta,
            fontFamily = Maquina, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
