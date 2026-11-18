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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.euromillones.app.data.Draw
import com.euromillones.app.data.Mask
import com.euromillones.app.data.MaskScope
import com.euromillones.app.data.PatternStat
import com.euromillones.app.data.maskOf
import com.euromillones.app.data.statOf
import com.euromillones.app.ui.components.Ball
import com.euromillones.app.ui.components.DrawCard
import com.euromillones.app.ui.components.MaskText
import com.euromillones.app.ui.components.STARS_LAST
import com.euromillones.app.ui.components.color
import com.euromillones.app.ui.components.pretty
import java.time.format.DateTimeFormatter
import java.util.Locale

private val es = Locale.forLanguageTag("es-ES")
private val shortFmt = DateTimeFormatter.ofPattern("dd/MM/yy")

internal fun pctStr(x: Double) = when {
    x >= 0.1 -> String.format(es, "%.1f %%", x * 100)
    x >= 0.001 -> String.format(es, "%.2f %%", x * 100)
    else -> String.format(es, "%.4f %%", x * 100)
}

internal fun num1(x: Double) = String.format(es, "%.1f", x)
internal fun intStr(x: Double) = String.format(es, "%,.0f", x)

@Composable
fun MasksScreen(draws: List<Draw>, modifier: Modifier = Modifier) {
    if (draws.isEmpty()) {
        Text("Sin datos todavía. Pulsa ⟳ para actualizar.", modifier.padding(24.dp))
        return
    }
    var scope by rememberSaveable { mutableStateOf(MaskScope.NUMEROS) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val selStat = remember(draws, scope, selected) { selected?.let { statOf(scope, it, draws) } }
    val selDraws = remember(draws, scope, selected) {
        selected?.let { m -> draws.filter { maskOf(scope, it) == m } }.orEmpty()
    }
    LaunchedEffect(selected) { if (selected != null) listState.animateScrollToItem(0) }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selStat != null) {
            item { SelectedMaskCard(selStat, scope, onClear = { selected = null }) }
            item {
                Text("Sorteos con esta máscara · ${selDraws.size}", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall)
            }
            items(selDraws, key = { "sel-${it.date}" }) { DrawCard(it) }
            item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
        }

        item { Text(Mask.RULES, style = MaterialTheme.typography.bodySmall) }
        item {
            Text("Último sorteo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        item { MaskedDrawCard(draws.first()) { selected = maskOf(scope, draws.first()) } }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Comparar:", style = MaterialTheme.typography.bodySmall)
                FilterChip(
                    selected = scope == MaskScope.NUMEROS,
                    onClick = { scope = MaskScope.NUMEROS; selected = null },
                    label = { Text("Solo números") },
                )
                FilterChip(
                    selected = scope == MaskScope.COMPLETA,
                    onClick = { scope = MaskScope.COMPLETA; selected = null },
                    label = { Text("Con estrellas") },
                )
            }
        }
        item {
            Text(
                "Histórico de máscaras · toca una para ver cuántas veces ha salido",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        items(draws, key = { "row-${it.date}" }) { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { selected = maskOf(scope, d) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(d.date.format(shortFmt), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
                MaskText(Mask.full(d), starsTokenIndex = STARS_LAST, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SelectedMaskCard(s: PatternStat, scope: MaskScope, onClear: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MaskText(
                    s.mask,
                    starsTokenIndex = if (scope == MaskScope.COMPLETA) STARS_LAST else -1,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) { Text("Quitar") }
            }
            Text("Probabilidad en el próximo sorteo: ${pctStr(s.probability)} (1 de cada ${intStr(s.averageGap)})",
                style = MaterialTheme.typography.bodySmall)
            Text("Ha salido ${s.count} veces · esperadas ${num1(s.expectedCount)}",
                style = MaterialTheme.typography.bodySmall)
            Text(
                when (val n = s.drawsSince) {
                    null -> "Nunca ha salido"
                    0 -> "Salió en el último sorteo"
                    else -> "Última: ${s.last?.format(shortFmt)} · lleva $n sorteos sin salir"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Sorteo con las dos letras (decena+unidad) debajo de cada número y la letra de cada estrella. */
@Composable
fun MaskedDrawCard(draw: Draw, onClick: (() -> Unit)? = null) {
    Card(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(draw.date.pretty(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                draw.numbers.sorted().forEach { n ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Ball(n, isStar = false, size = 36.dp)
                        Row {
                            Letter(Mask.dec(n))
                            Letter(Mask.uni(n))
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                draw.stars.sorted().forEach { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Ball(s, isStar = true, size = 36.dp)
                        Letter(Mask.star(s))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            MaskText(Mask.full(draw), starsTokenIndex = STARS_LAST, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Letter(l: com.euromillones.app.data.Level) {
    Text(l.name, color = l.color(), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall)
}
