package com.euromillones.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.euromillones.app.data.DigitHotCold
import com.euromillones.app.data.Gordo
import com.euromillones.app.data.NAVIDAD_TOTAL
import com.euromillones.app.data.NAVIDAD_WINDOWS
import com.euromillones.app.data.Navidad
import com.euromillones.app.data.currentDigitHotCold
import com.euromillones.app.data.historicDigitHotCold
import com.euromillones.app.data.navidadAllowed
import com.euromillones.app.data.navidadCount
import com.euromillones.app.ui.components.HeaderCell
import com.euromillones.app.ui.components.TextCell
import com.euromillones.app.ui.components.TokenCell

enum class NavCriterion(val title: String) { LMH("Dígitos"), CTF("Frío/Caliente"), AMBOS("Ambos") }

private class NavRow(val gordo: Gordo, val lmh: String, val ctf: String?, val tokens: List<String>?, val combos: Long)

@Composable
fun NavidadScreen(
    gordos: List<Gordo>,
    window: Int,
    onWindowChange: (Int) -> Unit,
    onOpen: (lmh: String, ctf: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (gordos.isEmpty()) {
        Text("Cargando los Gordos…", modifier.padding(24.dp))
        return
    }
    var criterion by rememberSaveable { mutableStateOf(NavCriterion.LMH) }
    val now = remember(gordos, window) { currentDigitHotCold(gordos, window) }
    val hist = remember(gordos, window) { historicDigitHotCold(gordos, window) }
    val rows = remember(gordos, hist, criterion) {
        gordos.mapIndexed { i, g ->
            val lmh = Navidad.lmh(g.number)
            val hc = hist[i]
            val ctf = hc?.ctf(g.number)
            val tokens: List<String>? = when (criterion) {
                NavCriterion.LMH -> lmh.map { it.toString() }
                NavCriterion.CTF -> ctf?.map { it.toString() }
                NavCriterion.AMBOS -> ctf?.let { c -> lmh.zip(c) { a, b -> "$a$b" } }
            }
            val combos = when {
                tokens == null -> 0L
                criterion == NavCriterion.LMH -> navidadCount(navidadAllowed(lmh, "-----", hc ?: now))
                else -> navidadCount(navidadAllowed(if (criterion == NavCriterion.AMBOS) lmh else "-----", ctf!!, hc!!))
            }
            NavRow(g, lmh, ctf, tokens, combos)
        }
    }
    val counts = remember(rows) { rows.mapNotNull { it.tokens?.joinToString("") }.groupingBy { it }.eachCount() }
    val hScroll = rememberScrollState()
    val tw = if (criterion == NavCriterion.AMBOS) 38.dp else 30.dp

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Lotería de Navidad · sorteo el 22 de diciembre", fontWeight = FontWeight.Bold)
                    Text(
                        "Último Gordo: ${gordos.first().number} (${gordos.first().year}) · ${gordos.size} Gordos desde ${gordos.last().year}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Cada cifra lleva una letra: L 0-2 · M 3-6 · H 7-9. Y en cada posición (D1…D5), " +
                            "C = los 3 dígitos que más han salido en los últimos $window Gordos, T = los 4 del medio, F = los 3 que menos.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Últimos Gordos:", style = MaterialTheme.typography.bodySmall)
                        NAVIDAD_WINDOWS.forEach { w ->
                            FilterChip(selected = w == window, onClick = { onWindowChange(w) }, label = { Text("$w") })
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        item {
            Text("Calientes y fríos ahora (por posición)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            HotColdTable(now)
            Text(
                "Ojo con D1: hasta 2010 había menos de 100.000 números, así que el 8 y el 9 casi no podían salir en la primera cifra. " +
                    "Por eso en D1 salen fríos por estructura, no por suerte.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Spacer(Modifier.height(6.dp))
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NavCriterion.entries.forEach { c ->
                    FilterChip(selected = c == criterion, onClick = { criterion = c }, label = { Text(c.title) })
                }
            }
            Text(
                "Histórico de Gordos · toca una fila para generar números con esa máscara. " +
                    "Prob. = con las reglas actuales (100.000 números).",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Row(Modifier.horizontalScroll(hScroll)) {
                HeaderCell("Año", 44.dp)
                HeaderCell("Número", 60.dp)
                (1..5).forEach { HeaderCell("D$it", tw) }
                HeaderCell("Comb.", 56.dp)
                HeaderCell("Prob.", 60.dp)
                HeaderCell("Veces", 44.dp)
            }
        }

        itemsIndexed(rows, key = { i, r -> "nav-$i-${r.gordo.number}" }) { i, r ->
            val tokens = r.tokens ?: return@itemsIndexed
            val bg = if (i % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent
            Row(
                Modifier
                    .horizontalScroll(hScroll)
                    .clickable {
                        onOpen(
                            if (criterion == NavCriterion.CTF) "-----" else r.lmh,
                            if (criterion == NavCriterion.LMH) "-----" else (r.ctf ?: "-----"),
                        )
                    },
            ) {
                TextCell("${r.gordo.year}", 44.dp, background = bg)
                TextCell(r.gordo.number, 60.dp, bold = true, background = bg)
                tokens.forEach { TokenCell(it, false, tw) }
                TextCell(intStr(r.combos), 56.dp, background = bg)
                TextCell(pctStr(r.combos.toDouble() / NAVIDAD_TOTAL), 60.dp, background = bg)
                val c = counts[tokens.joinToString("")] ?: 0
                TextCell("$c", 44.dp, bold = c > 1, background = bg)
            }
        }
    }
}

@Composable
internal fun HotColdTable(hc: DigitHotCold) {
    Column {
        Row {
            HeaderCell("Pos.", 44.dp)
            HeaderCell("C caliente", 90.dp)
            HeaderCell("T templado", 100.dp)
            HeaderCell("F frío", 90.dp)
        }
        (0 until 5).forEach { pos ->
            Row {
                TextCell("D${pos + 1}", 44.dp, bold = true)
                TextCell(hc.digitsWith(pos, 'C').joinToString(" "), 90.dp)
                TextCell(hc.digitsWith(pos, 'T').joinToString(" "), 100.dp)
                TextCell(hc.digitsWith(pos, 'F').joinToString(" "), 90.dp)
            }
        }
    }
}

@Composable
internal fun BigNumber(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
}
