package com.euromillones.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.euromillones.app.data.GenTarget
import com.euromillones.app.data.MaskScope
import com.euromillones.app.data.PatternStat
import com.euromillones.app.data.StatSort
import com.euromillones.app.data.computeStats
import com.euromillones.app.data.sortedFor
import com.euromillones.app.ui.components.HeaderCell
import com.euromillones.app.ui.components.MaskCells
import com.euromillones.app.ui.components.MaskHeaderCells
import com.euromillones.app.ui.components.TextCell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Orange = Color(0xFFEA580C)
private val CW = 28.dp
private val W_RANK = 36.dp
private val W_PROB = 52.dp
private val W_COUNT = 40.dp
private val W_SINCE = 44.dp

private class StatsData(
    val lists: Map<MaskScope, List<PatternStat>>,
    val tops: Map<MaskScope, Pair<PatternStat, Int>>,
)

@Composable
fun StatsScreen(draws: List<Draw>, onOpenMask: (GenTarget) -> Unit, modifier: Modifier = Modifier) {
    var scope by rememberSaveable { mutableStateOf(MaskScope.COMPLETA) }
    var sort by rememberSaveable { mutableStateOf(StatSort.PROBABLES) }
    var limit by rememberSaveable { mutableIntStateOf(50) }

    if (draws.isEmpty()) {
        Text("Sin datos todavía. Pulsa ⟳ para actualizar.", modifier.padding(24.dp))
        return
    }

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

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
        // ---- Resumen ----
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Lo más probable en el próximo sorteo", fontWeight = FontWeight.Bold)
                    Text("Toca una para generar combinaciones", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    MaskScope.entries.forEach { k ->
                        val (top, ties) = d.tops.getValue(k)
                        Text(
                            k.title + if (ties > 1) " (empatada con ${ties - 1} más)" else "",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(
                            Modifier.clickable { onOpenMask(GenTarget.fromScope(k, top.mask)) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MaskCells(top.mask, k)
                            Text(
                                "  ${pctStr(top.probability)}",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ---- Filtros ----
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(MaskScope.entries) { k ->
                    FilterChip(selected = k == scope, onClick = { scope = k; limit = 50 }, label = { Text(k.title) })
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(StatSort.entries) { s ->
                    FilterChip(selected = s == sort, onClick = { sort = s; limit = 50 }, label = { Text(s.title) })
                }
            }
            Text(
                when (sort) {
                    StatSort.PROBABLES -> "Probabilidad real de salir en el próximo sorteo (cálculo exacto). " +
                        "Es lo único que hace una máscara más probable que otra."
                    StatSort.SALIDAS -> "Las que más veces han salido desde 2004."
                    StatSort.RETRASADAS -> "Sorteos sin salir frente a su media (naranja = más de 1,5 veces). " +
                        "Cada sorteo es independiente: el retraso no aumenta la probabilidad."
                } + "  ${list.size} máscaras posibles · toca una fila para generar.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        // ---- Tabla ----
        item {
            Row {
                HeaderCell("#", W_RANK)
                MaskHeaderCells(scope, CW)
                HeaderCell("Prob.", W_PROB)
                HeaderCell("Veces", W_COUNT)
                HeaderCell("Sin salir", W_SINCE)
            }
        }
        itemsIndexed(list.take(limit), key = { _, it -> it.mask }) { i, s ->
            val bg = if (i % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent
            val late = s.count > 0 && s.delayRatio(draws.size) >= 1.5
            Row(Modifier.clickable { onOpenMask(GenTarget.fromScope(scope, s.mask)) }) {
                TextCell("${i + 1}", W_RANK, background = bg)
                MaskCells(s.mask, scope, CW)
                TextCell(pctStr(s.probability), W_PROB, bold = true, background = bg)
                TextCell("${s.count}", W_COUNT, background = bg)
                TextCell(
                    s.drawsSince?.toString() ?: "nunca",
                    W_SINCE,
                    color = if (late) Color.White else Color.Unspecified,
                    bold = late,
                    background = if (late) Orange else bg,
                )
            }
        }
        if (list.size > limit) {
            item {
                TextButton(onClick = { limit += 100 }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ver más (${list.size - limit} restantes)")
                }
            }
        }
    }
}
