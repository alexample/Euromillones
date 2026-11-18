package com.euromillones.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.euromillones.app.data.Draw
import com.euromillones.app.data.MaskKey
import com.euromillones.app.data.PatternStat
import com.euromillones.app.data.StatKind
import com.euromillones.app.data.StatSort
import com.euromillones.app.data.computeStats
import com.euromillones.app.data.sortedFor
import com.euromillones.app.data.Level
import java.time.format.DateTimeFormatter
import java.util.Locale

private val es = Locale.forLanguageTag("es-ES")
private val dFmt = DateTimeFormatter.ofPattern("dd/MM/yy")
private val Orange = Color(0xFFEA580C)

private fun pct2(x: Double) = String.format(es, if (x >= 0.1) "%.1f %%" else "%.2f %%", x * 100)
private fun num1(x: Double) = String.format(es, "%.1f", x)

@Composable
fun StatsScreen(draws: List<Draw>, modifier: Modifier = Modifier) {
    var kind by rememberSaveable { mutableStateOf(StatKind.COMBINADA) }
    var sort by rememberSaveable { mutableStateOf(StatSort.PROBABLES) }
    var limit by rememberSaveable { mutableIntStateOf(30) }

    if (draws.isEmpty()) {
        Text("Sin datos todavía. Pulsa ⟳ para actualizar.", modifier.padding(24.dp))
        return
    }

    val all = remember(draws) { StatKind.entries.associateWith { computeStats(it, draws) } }
    val list = remember(all, kind, sort) { all.getValue(kind).sortedFor(sort, draws.size) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- Resumen: lo más probable en el próximo sorteo ----
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        "Lo más probable en el próximo sorteo",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.padding(top = 4.dp))
                    StatKind.entries.forEach { k ->
                        val top = all.getValue(k).maxBy { it.probability }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                "${k.title}:",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(78.dp),
                            )
                            MaskParts(top, onDark = true)
                            Spacer(Modifier.weight(1f))
                            Text(
                                pct2(top.probability),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        // ---- Filtros ----
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(StatKind.entries) { k ->
                    FilterChip(selected = k == kind, onClick = { kind = k; limit = 30 }, label = { Text(k.title) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatSort.entries.forEach { s ->
                    FilterChip(selected = s == sort, onClick = { sort = s; limit = 30 }, label = { Text(s.title) })
                }
            }
        }
        item {
            Text(
                if (sort == StatSort.PROBABLES)
                    "Probabilidad real de que salga en el próximo sorteo (cálculo exacto). " +
                        "Es la única que de verdad hace una máscara más probable que otra."
                else
                    "Retraso = sorteos sin salir comparado con su media. " +
                        "Ojo: cada sorteo es independiente; el retraso no aumenta la probabilidad, sirve solo como seguimiento.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---- Lista ----
        itemsIndexed(list.take(limit), key = { _, it -> it.id }) { i, s -> StatCard(s, i + 1, draws.size) }
        if (list.size > limit) {
            item {
                TextButton(onClick = { limit += 50 }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ver más (${list.size - limit} restantes)")
                }
            }
        }
    }
}

@Composable
private fun StatCard(s: PatternStat, rank: Int, totalDraws: Int) {
    val ratio = s.delayRatio(totalDraws)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$rank", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
                MaskParts(s)
                Spacer(Modifier.weight(1f))
                Text(pct2(s.probability), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                "Sale 1 de cada ${num1(s.averageGap)} sorteos · ha salido ${s.count} veces (esperadas ${num1(s.expectedCount)})",
                style = MaterialTheme.typography.bodySmall,
            )
            val sinceText = when (val n = s.drawsSince) {
                null -> "Nunca ha salido"
                0 -> "Salió en el último sorteo (${s.last?.format(dFmt)})"
                else -> "Última: ${s.last?.format(dFmt)} · lleva $n sorteos sin salir"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sinceText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (ratio >= 1.5) {
                    Text(
                        "retraso ×${num1(ratio)}",
                        color = Orange,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/** "Dec 2L 2M 1H · Uni 1L 2M 2H" con colores por nivel. */
@Composable
private fun MaskParts(s: PatternStat, onDark: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        s.parts.forEachIndexed { i, (tag, key) ->
            if (i > 0) Text(" · ", style = MaterialTheme.typography.bodySmall, color = if (onDark) Color.White else Color.Unspecified)
            if (s.parts.size > 1) {
                Text("$tag ", style = MaterialTheme.typography.labelSmall, color = if (onDark) Color.White else Color.Unspecified)
            }
            KeyText(key, onDark)
        }
    }
}

@Composable
private fun KeyText(k: MaskKey, onDark: Boolean) {
    if (onDark) {
        Text(k.label, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
    } else {
        Row {
            LevelText("${k.l}L", Level.L)
            Spacer(Modifier.width(2.dp))
            LevelText("${k.m}M", Level.M)
            Spacer(Modifier.width(2.dp))
            LevelText("${k.h}H", Level.H)
        }
    }
}
