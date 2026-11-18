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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.euromillones.app.data.Draw
import com.euromillones.app.data.GenTarget
import com.euromillones.app.data.MaskScope
import com.euromillones.app.data.maskOf
import com.euromillones.app.ui.components.BallsWithLetters
import com.euromillones.app.ui.components.HeaderCell
import com.euromillones.app.ui.components.MaskCells
import com.euromillones.app.ui.components.MaskHeaderCells
import com.euromillones.app.ui.components.MaskLegend
import com.euromillones.app.ui.components.TextCell
import com.euromillones.app.ui.components.pretty
import java.time.format.DateTimeFormatter
import java.util.Locale

private val es = Locale.forLanguageTag("es-ES")
internal val shortFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")

internal fun pctStr(x: Double) = when {
    x >= 0.1 -> String.format(es, "%.1f%%", x * 100)
    x >= 0.001 -> String.format(es, "%.2f%%", x * 100)
    else -> String.format(es, "%.4f%%", x * 100)
}

internal fun num1(x: Double) = String.format(es, "%.1f", x)
internal fun intStr(x: Double) = String.format(es, "%,.0f", x)
internal fun intStr(x: Long) = String.format(es, "%,d", x)

@Composable
fun MasksScreen(draws: List<Draw>, onOpenMask: (GenTarget) -> Unit, modifier: Modifier = Modifier) {
    if (draws.isEmpty()) {
        Text("Sin datos todavía. Pulsa ⟳ para actualizar.", modifier.padding(24.dp))
        return
    }
    var scope by rememberSaveable { mutableStateOf(MaskScope.COMPLETA) }
    val counts = remember(draws, scope) { draws.groupingBy { maskOf(scope, it) }.eachCount() }
    val last = draws.first()

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
        item {
            Text("Criterio", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            MaskLegend()
            Spacer(Modifier.height(12.dp))
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Último sorteo · ${last.date.pretty()}", fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    BallsWithLetters(last.numbers, last.stars)
                    Spacer(Modifier.height(6.dp))
                    Row { MaskHeaderCells(MaskScope.COMPLETA) }
                    Row { MaskCells(maskOf(MaskScope.COMPLETA, last), MaskScope.COMPLETA) }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onOpenMask(GenTarget.fromScope(scope, maskOf(scope, last))) }) {
                        Text("Generar con esta máscara")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = scope == MaskScope.COMPLETA,
                    onClick = { scope = MaskScope.COMPLETA },
                    label = { Text("Con estrellas") },
                )
                FilterChip(
                    selected = scope == MaskScope.NUMEROS,
                    onClick = { scope = MaskScope.NUMEROS },
                    label = { Text("Solo números") },
                )
            }
            Text(
                "Histórico · toca una fila para generar combinaciones con esa máscara",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Row {
                HeaderCell("Fecha", 66.dp)
                MaskHeaderCells(scope)
                HeaderCell("Veces", 48.dp)
            }
        }

        itemsIndexed(draws, key = { _, d -> "row-${d.date}" }) { i, d ->
            val m = maskOf(scope, d)
            val bg = if (i % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else androidx.compose.ui.graphics.Color.Transparent
            Row(Modifier.clickable { onOpenMask(GenTarget.fromScope(scope, m)) }) {
                TextCell(d.date.format(shortFmt), 66.dp, background = bg)
                MaskCells(m, scope)
                TextCell("${counts[m] ?: 0}", 48.dp, bold = (counts[m] ?: 0) > 1, background = bg)
            }
        }
    }
}
