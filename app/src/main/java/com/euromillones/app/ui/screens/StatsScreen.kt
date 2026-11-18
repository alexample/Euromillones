package com.euromillones.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.euromillones.app.data.Draw
import com.euromillones.app.data.MaskScope
import com.euromillones.app.data.PatternStat
import com.euromillones.app.data.StatSort
import com.euromillones.app.data.computeStats
import com.euromillones.app.data.sortedFor
import com.euromillones.app.ui.components.MaskText
import com.euromillones.app.ui.components.STARS_LAST
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

private val dFmt = DateTimeFormatter.ofPattern("dd/MM/yy")
private val Orange = Color(0xFFEA580C)

private class StatsData(
    val lists: Map<MaskScope, List<PatternStat>>,
    val tops: Map<MaskScope, Pair<PatternStat, Int>>, // mejor + cuántas empatan con ella
)

@Composable
fun StatsScreen(draws: List<Draw>, modifier: Modifier = Modifier) {
    var scope by rememberSaveable { mutableStateOf(MaskScope.COMPLETA) }
    var sort by rememberSaveable { mutableStateOf(StatSort.PROBABLES) }
    var limit by rememberSaveable { mutableIntStateOf(30) }

    if (draws.isEmpty()) {
        Text("Sin datos todavía. Pulsa ⟳ para actualizar.", modifier.padding(24.dp))
        return
    }

    // El cálculo recorre los 2,1 millones de combinaciones: se hace en segundo plano
    val data by produceState<StatsData?>(null, draws) {
        value = withContext(Dispatchers.Default) {
            val lists = MaskScope.entries.associateWith { computeStats(it, draws) }
            val tops = lists.mapValues { (_, l) ->
                val best = l.maxWith(compareBy<PatternStat> { it.probability }.thenBy { it.count })
                best to l.count { kotlin.math.abs(it.probability - best.probability) < 1e-12 }
            }
            StatsData(lists, tops)
        }
    }
    val sorted by produceState<List<PatternStat>?>(null, data, scope, sort) {
        val d = data ?: return@produceState
        value = withContext(Dispatchers.Default) { d.lists.getValue(scope).sortedFor(sort, draws.size) }
    }

    val d = data
    val list = sorted
    if (d == null || list == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Calculando probabilidades de todas las combinaciones…", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Lo más probable en el próximo sorteo", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    MaskScope.entries.forEach { k ->
                        val (top, ties) = d.tops.getValue(k)
                        Text("${k.title}:", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MaskText(
                                top.mask,
                                starsTokenIndex = when (k) {
                                    MaskScope.COMPLETA -> STARS_LAST
                                    MaskScope.ESTRELLAS -> 0
                                    else -> -1
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(pctStr(top.probability), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (ties > 1) {
                            Text("(empatada con otras ${ties - 1} máscaras)", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(MaskScope.entries) { k ->
                    FilterChip(selected = k == scope, onClick = { scope = k; limit = 30 }, label = { Text(k.title) })
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(StatSort.entries) { s ->
                    FilterChip(selected = s == sort, onClick = { sort = s; limit = 30 }, label = { Text(s.title) })
                }
            }
        }
        item {
            Text(
                when (sort) {
                    StatSort.PROBABLES -> "Probabilidad real de salir en el próximo sorteo (cálculo exacto sobre todas las combinaciones). " +
                        "Es lo único que hace que una máscara sea más probable que otra."
                    StatSort.SALIDAS -> "Las que más veces han salido desde 2004, comparadas con las que se esperaban."
                    StatSort.RETRASADAS -> "Sorteos sin salir comparado con su media. Ojo: cada sorteo es independiente, " +
                        "el retraso no aumenta la probabilidad; sirve como seguimiento."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Text("${list.size} máscaras posibles", style = MaterialTheme.typography.labelMedium)
        }

        itemsIndexed(list.take(limit), key = { _, it -> it.mask }) { i, s -> StatCard(s, i + 1, scope, draws.size) }
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
private fun StatCard(s: PatternStat, rank: Int, scope: MaskScope, totalDraws: Int) {
    val ratio = s.delayRatio(totalDraws)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$rank", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                MaskText(
                    s.mask,
                    starsTokenIndex = when (scope) {
                        MaskScope.COMPLETA -> STARS_LAST
                        MaskScope.ESTRELLAS -> 0
                        else -> -1
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(pctStr(s.probability), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Sale 1 de cada ${intStr(s.averageGap)} sorteos · ha salido ${s.count} veces (esperadas ${num1(s.expectedCount)})",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (val n = s.drawsSince) {
                        null -> "Nunca ha salido"
                        0 -> "Salió en el último sorteo (${s.last?.format(dFmt)})"
                        else -> "Última: ${s.last?.format(dFmt)} · lleva $n sorteos sin salir"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (ratio >= 1.5 && s.count > 0) {
                    Text("retraso ×${num1(ratio)}", color = Orange, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
