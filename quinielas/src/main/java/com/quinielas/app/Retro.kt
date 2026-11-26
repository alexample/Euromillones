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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
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
val Papel = Color(0xFFEFE3B4)
val PapelOscuro = Color(0xFFE4D49C)
val Tinta = Color(0xFFE2532F)          // naranja-rojo de imprenta del boleto
val TintaSuave = Color(0xFFF6C9B4)
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

// --- Boleto de 1984: papel amarillento, tinta naranja-roja, cruces de bolígrafo ---
val PapelBoleto = Color(0xFFEFE3B4)
val TintaBoleto = Color(0xFFE2532F)
val Boligrafo = Color(0xFF2E2E3A)

private val Mano: FontFamily = runCatching {
    val tf = Typeface.create("casual", Typeface.NORMAL)
    if (tf == Typeface.DEFAULT) FontFamily.Cursive else FontFamily(tf)
}.getOrDefault(FontFamily.Cursive)

private val Estrecha: FontFamily = runCatching { FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL)) }.getOrDefault(FontFamily.SansSerif)

private val CEL = 12.dp       // casilla
private val FILA = 18.dp

/** Casilla del boleto; la marcada lleva una cruz de bolígrafo un poco torcida. */
@Composable
private fun Casilla(marcada: Boolean, giro: Float) {
    Box(Modifier.size(CEL).border(0.8.dp, TintaBoleto), contentAlignment = Alignment.Center) {
        if (marcada) Text("X", color = Boligrafo, fontSize = 13.sp, fontFamily = Mano, fontWeight = FontWeight.Bold, modifier = Modifier.rotate(giro))
    }
}

/** Líneas onduladas del número de serie (como el fondo de seguridad del resguardo). */
@Composable
private fun Ondas(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val paso = size.height / 7
        for (k in 0 until 7) {
            val y0 = paso * (k + 0.5f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, y0)
                var x = 0f
                while (x < size.width) { quadraticBezierTo(x + 6f, y0 - 3f, x + 12f, y0); quadraticBezierTo(x + 18f, y0 + 3f, x + 24f, y0); x += 24f }
            }
            drawPath(path, TintaBoleto.copy(alpha = 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
        }
    }
}

/**
 * Boleto como el de la temporada 83/84: cabecera con el pleno, la clave, la jornada, la fecha y el número de
 * resguardo; 14 partidos con el pronóstico apuntado a mano en el margen y 8 apuestas de casillas 1 X 2.
 */
@Composable
fun Boleto(
    numero: Int, primera: Int, partidos: List<PartidoProx>, columnas: List<Int>, jornada: Int?,
    fecha: String = "", margen: String? = null,
) {
    val serie = remember(columnas, numero) { (columnas.fold(numero * 7919) { a, c -> a * 31 + c } and 0x7FFFFFFF) % 90_000_000 + 10_000_000 }
    val clave = "ABCDEFGH"[serie % 8]
    val giros = remember(columnas) { val r = kotlin.random.Random(serie); FloatArray(14 * 8) { r.nextFloat() * 24f - 12f } }
    val f = runCatching { java.time.LocalDate.parse(fecha.take(10)) }.getOrNull()
    Column(Modifier.background(PapelBoleto).border(1.dp, TintaBoleto.copy(alpha = 0.6f)).padding(6.dp).horizontalScroll(rememberScrollState())) {
        // ---------- Cabecera ----------
        Row(Modifier.height(86.dp)) {
            // Sello rojo de las apuestas (vertical)
            Box(Modifier.width(38.dp).fillMaxHeight().background(TintaBoleto).padding(2.dp).background(Color(0xFFF7E9E2)).border(1.dp, TintaBoleto),
                contentAlignment = Alignment.Center) {
                Text("${columnas.size} APUESTAS  ZA$serie".take(22), color = TintaBoleto, fontFamily = Estrecha, fontWeight = FontWeight.Bold,
                    fontSize = 9.sp, maxLines = 1, softWrap = false, modifier = Modifier.rotate(90f).width(80.dp))
            }
            // Pleno y reclamaciones
            Column(Modifier.width(190.dp).fillMaxHeight().border(1.dp, Texto.copy(alpha = 0.7f)).padding(4.dp)) {
                val p15 = partidos.getOrNull(14)
                Text("PLENO AL 15:", fontFamily = Estrecha, fontSize = 10.sp, color = Texto, fontWeight = FontWeight.SemiBold)
                Text(p15?.let { "  ${it.local}-${it.visit}." } ?: "  —", fontFamily = Estrecha, fontSize = 10.sp, color = Texto, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text("RECLAMACIONES: (Véase el dorso)", fontFamily = Estrecha, fontSize = 8.sp, color = Texto)
            }
            // Clave, jornada y fecha
            Column(Modifier.width(66.dp).fillMaxHeight().border(1.dp, Texto.copy(alpha = 0.7f)), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Clave $clave", fontFamily = Estrecha, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Texto)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Texto.copy(alpha = 0.7f)))
                Text("Jornada", fontFamily = Estrecha, fontSize = 8.sp, color = Texto)
                Text("${jornada ?: "—"}", fontFamily = Estrecha, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Texto)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Texto.copy(alpha = 0.7f)))
                Text(f?.let { "${it.dayOfMonth}-${it.monthValue}-${it.year}" } ?: "", fontFamily = Estrecha, fontSize = 10.sp, color = Texto)
            }
            // Número de resguardo
            Column(Modifier.width(110.dp).fillMaxHeight().border(1.dp, Texto.copy(alpha = 0.7f)), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    Ondas(Modifier.fillMaxWidth().height(56.dp))
                    Text("$serie", color = TintaBoleto, fontFamily = Maquina, fontSize = 17.sp, letterSpacing = 1.sp)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Texto.copy(alpha = 0.7f)))
                Text("RESGUARDO", fontFamily = Estrecha, fontSize = 12.sp, color = Texto, modifier = Modifier.padding(top = 4.dp))
            }
        }
        // ---------- Cuerpo ----------
        Row {
            // Aviso vertical
            Box(Modifier.width(16.dp).height(FILA * 15), contentAlignment = Alignment.Center) {
                Text("ATENCIÓN · RELLENE LAS CASILLAS CON EL SIGNO «X»", color = Texto, fontFamily = Estrecha, fontSize = 7.sp,
                    maxLines = 1, softWrap = false, modifier = Modifier.rotate(-90f).width(FILA * 15))
            }
            // Partidos con el pronóstico a mano en el margen
            Column(Modifier.width(150.dp)) {
                Box(Modifier.height(FILA))
                for (i in 0 until 14) {
                    val p = partidos.getOrNull(i)
                    Row(Modifier.height(FILA), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${i + 1} " + (p?.let { "${abrev(it.local)}-${abrev(it.visit)}" } ?: "") + " ..........................",
                            fontFamily = Estrecha, fontSize = 9.sp, color = Texto, maxLines = 1, softWrap = false,
                            overflow = TextOverflow.Clip, modifier = Modifier.weight(1f),
                        )
                        Text(margen?.getOrNull(i)?.let { if (it == 'X') "X" else it.toString() } ?: "", fontFamily = Mano, fontSize = 15.sp,
                            color = Color(0xFFB8322A), modifier = Modifier.width(14.dp))
                    }
                    if (i == 3 || i == 7 || i == 11) Box(Modifier.width(150.dp).height(1.5.dp).background(TintaBoleto))
                }
            }
            // Las 8 apuestas
            Column {
                Row(Modifier.height(FILA), verticalAlignment = Alignment.CenterVertically) {
                    repeat(8) { a ->
                        Row(Modifier.padding(start = if (a == 0) 2.dp else 4.dp)) {
                            for (s in SIGNOS) Text(s.toString(), color = TintaBoleto, fontFamily = Estrecha, fontWeight = FontWeight.Bold,
                                fontSize = 11.sp, modifier = Modifier.width(CEL + 1.dp))
                        }
                    }
                }
                for (i in 0 until 14) {
                    Row(Modifier.height(FILA), verticalAlignment = Alignment.CenterVertically) {
                        repeat(8) { a ->
                            val t = columnas.getOrNull(a)?.let { Generador.texto(it) }
                            Row(Modifier.padding(start = if (a == 0) 2.dp else 4.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                for (s in SIGNOS) Casilla(t != null && t[i] == s, giros[i * 8 + a])
                            }
                        }
                    }
                    if (i == 3 || i == 7 || i == 11) Box(Modifier.width((CEL * 3 + 6.dp) * 8).height(1.5.dp).background(TintaBoleto))
                }
                // Pie: número de apuesta
                Row(Modifier.height(FILA).background(TintaBoleto.copy(alpha = 0.12f)), verticalAlignment = Alignment.CenterVertically) {
                    repeat(8) { a ->
                        Text(if (a < columnas.size) "${primera + a}ª" else "", color = TintaBoleto, fontFamily = Estrecha, fontWeight = FontWeight.Bold,
                            fontSize = 10.sp, modifier = Modifier.padding(start = if (a == 0) 2.dp else 4.dp).width(CEL * 3 + 2.dp))
                    }
                }
            }
        }
        Text("A P U E S T A S   ·   ${columnas.size} × ${"%.2f".format(Generador.PRECIO)} €", color = TintaBoleto, fontFamily = Estrecha,
            fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 2.sp)
    }
}

/** Nombres como en los boletos de entonces: «R. MADRID», «AT. BILBAO», «D. CORUÑA». */
private fun abrev(n: String): String = n.uppercase()
    .replace("REAL ", "R. ").replace("ATLÉTICO ", "AT. ").replace("ATLETICO ", "AT. ").replace("DEPORTIVO", "D. CORUÑA")
    .replace("ATHLETIC CLUB", "AT. BILBAO").replace("ATHLETIC", "AT. BILBAO").take(13)
