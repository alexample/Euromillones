package com.euromillones.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.euromillones.app.data.Draw
import com.euromillones.app.data.GenTarget
import com.euromillones.app.data.HOT_WINDOWS
import com.euromillones.app.data.Mask
import com.euromillones.app.data.historicTempMasks
import com.euromillones.app.ui.components.BallsWithLetters
import com.euromillones.app.ui.components.HeaderCell
import com.euromillones.app.ui.components.MaskLegend
import com.euromillones.app.ui.components.TextCell
import com.euromillones.app.ui.components.TokenCell
import com.euromillones.app.ui.components.pretty
import java.time.format.DateTimeFormatter
import java.util.Locale

private val es = Locale.forLanguageTag("es-ES")
internal val maskDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")

internal fun pctStr(x: Double) = when {
    x >= 0.1 -> String.format(es, "%.1f%%", x * 100)
    x >= 0.001 -> String.format(es, "%.2f%%", x * 100)
    x >= 0.00001 -> String.format(es, "%.4f%%", x * 100)
    else -> String.format(es, "%.7f%%", x * 100)
}

internal fun num1(x: Double) = String.format(es, "%.1f", x)
internal fun intStr(x: Double) = String.format(es, "%,.0f", x)
internal fun intStr(x: Long) = String.format(es, "%,d", x)

/** Qué letras forman la máscara. */
enum class Criterion(val title: String) {
    LMH("Decena/Unidad"),
    CTF("Frío/Caliente"),
    AMBOS("Ambos"),
}

/** Bloques de la máscara de un sorteo según el criterio. Devuelve null si no hay datos frío/caliente. */
internal fun maskTokens(
    d: Draw,
    criterion: Criterion,
    withStars: Boolean,
    temp: Pair<String, String>?,
): List<String>? {
    val lmh = d.numbers.sorted().map { Mask.token(it) }
    val lmhStar = Mask.stars(d)
    if (criterion != Criterion.LMH && temp == null) return null
    val ctf = temp?.first?.split(" ").orEmpty()
    val ctfStar = temp?.second.orEmpty()
    val nums = when (criterion) {
        Criterion.LMH -> lmh
        Criterion.CTF -> ctf
        Criterion.AMBOS -> lmh.zip(ctf) { a, b -> a + b }
    }
    if (!withStars) return nums
    val star = when (criterion) {
        Criterion.LMH -> lmhStar
        Criterion.CTF -> ctfStar
        Criterion.AMBOS -> "$lmhStar·$ctfStar"
    }
    return nums + star
}

internal fun targetFor(d: Draw, criterion: Criterion, withStars: Boolean, temp: Pair<String, String>?): GenTarget {
    val useLmh = criterion != Criterion.CTF
    val useTemp = criterion != Criterion.LMH && temp != null
    return GenTarget(
        numMask = if (useLmh) Mask.numbers(d) else null,
        starMask = if (useLmh && withStars) Mask.stars(d) else null,
        tempNum = if (useTemp) temp!!.first else null,
        tempStar = if (useTemp && withStars) temp!!.second else null,
    )
}

private fun numWidth(c: Criterion): Dp = if (c == Criterion.AMBOS) 38.dp else 30.dp
private fun starWidth(c: Criterion): Dp = if (c == Criterion.AMBOS) 52.dp else 30.dp

@Composable
fun MasksScreen(
    draws: List<Draw>,
    window: Int,
    onWindowChange: (Int) -> Unit,
    onOpenMask: (GenTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (draws.isEmpty()) {
        Text("Sin datos todavía. Pulsa ⟳ para actualizar.", modifier.padding(24.dp))
        return
    }
    var criterion by rememberSaveable { mutableStateOf(Criterion.LMH) }
    var withStars by rememberSaveable { mutableStateOf(true) }

    val temps = remember(draws, window) { historicTempMasks(draws, window) }
    val rows = remember(draws, criterion, withStars, temps) {
        draws.map { d -> d to maskTokens(d, criterion, withStars, temps[d.date]) }
    }
    val counts = remember(rows) {
        rows.mapNotNull { it.second?.joinToString(" ") }.groupingBy { it }.eachCount()
    }
    val last = draws.first()
    val lastTemp = temps[last.date]
    val nw = numWidth(criterion)
    val sw = starWidth(criterion)

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
        item {
            Text("Criterios", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            MaskLegend(window)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ventana frío/caliente:", style = MaterialTheme.typography.bodySmall)
                HOT_WINDOWS.forEach { w ->
                    FilterChip(selected = w == window, onClick = { onWindowChange(w) }, label = { Text("$w") })
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Último sorteo · ${last.date.pretty()}",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    val tNums = lastTemp?.first?.split(" ")
                    val tStars = lastTemp?.second
                    val sortedNums = last.numbers.sorted()
                    val sortedStars = last.stars.sorted()
                    BallsWithLetters(
                        last.numbers,
                        last.stars,
                        tempOf = tNums?.let { t -> { n: Int -> t.getOrElse(sortedNums.indexOf(n)) { "" } } },
                        starTempOf = tStars?.let { t -> { s: Int -> t.getOrNull(sortedStars.indexOf(s))?.toString() ?: "" } },
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onOpenMask(targetFor(last, criterion, withStars, lastTemp)) }) {
                        Text("Generar con esta máscara")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Criterion.entries) { c ->
                    FilterChip(selected = c == criterion, onClick = { criterion = c }, label = { Text(c.title) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = withStars, onClick = { withStars = true }, label = { Text("Con estrellas") })
                FilterChip(selected = !withStars, onClick = { withStars = false }, label = { Text("Solo números") })
            }
            Text(
                "Toca una fila para generar combinaciones con esa máscara." +
                    if (criterion != Criterion.LMH) " Frío/caliente = como estaba cada número justo antes de ese sorteo." else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Row {
                HeaderCell("Fecha", 58.dp)
                (1..5).forEach { HeaderCell("N$it", nw) }
                if (withStars) HeaderCell("★", sw)
                HeaderCell("Veces", 40.dp)
            }
        }

        itemsIndexed(rows, key = { _, r -> "row-${r.first.date}" }) { i, (d, tokens) ->
            if (tokens == null) return@itemsIndexed
            val m = tokens.joinToString(" ")
            val bg = if (i % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent
            Row(Modifier.clickable { onOpenMask(targetFor(d, criterion, withStars, temps[d.date])) }) {
                TextCell(d.date.format(maskDateFmt), 58.dp, background = bg)
                tokens.forEachIndexed { k, t ->
                    val isStar = withStars && k == tokens.lastIndex
                    TokenCell(t, isStar, if (isStar) sw else nw)
                }
                val c = counts[m] ?: 0
                TextCell("$c", 40.dp, bold = c > 1, background = bg)
            }
        }
    }
}
