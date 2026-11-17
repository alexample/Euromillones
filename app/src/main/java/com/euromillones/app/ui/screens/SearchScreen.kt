package com.euromillones.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.euromillones.app.data.Draw
import com.euromillones.app.data.MatchMode
import com.euromillones.app.data.SearchQuery
import com.euromillones.app.data.search
import com.euromillones.app.ui.components.Ball
import com.euromillones.app.ui.components.DrawCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val shortFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private enum class DateField { FROM, TO }

@Composable
fun SearchScreen(
    draws: List<Draw>,
    query: SearchQuery,
    onQueryChange: ((SearchQuery) -> SearchQuery) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf<DateField?>(null) }
    val results = remember(draws, query) { if (query.isEmpty) emptyList() else draws.search(query) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---------- FECHAS ----------
        item { SectionTitle("Fechas") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DateButton("Desde", query.from, Modifier.weight(1f)) { picking = DateField.FROM }
                DateButton("Hasta", query.to, Modifier.weight(1f)) { picking = DateField.TO }
                if (query.from != null || query.to != null) {
                    IconButton(onClick = { onQueryChange { it.copy(from = null, to = null) } }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Quitar fechas")
                    }
                }
            }
        }
        item {
            Text(
                "Para un día concreto, pon la misma fecha en «Desde» y «Hasta».",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---------- NÚMEROS ----------
        item { SectionTitle("Números (${query.numbers.size} elegidos)") }
        item {
            NumberGrid(range = 1..50, selected = query.numbers, isStar = false) { n ->
                onQueryChange { it.copy(numbers = it.numbers.toggle(n)) }
            }
        }
        item { SectionTitle("Estrellas (${query.stars.size} elegidas)") }
        item {
            NumberGrid(range = 1..12, selected = query.stars, isStar = true) { s ->
                onQueryChange { it.copy(stars = it.stars.toggle(s)) }
            }
        }

        // ---------- MODO ----------
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Que salga:", style = MaterialTheme.typography.bodyMedium)
                FilterChip(
                    selected = query.mode == MatchMode.ANY,
                    onClick = { onQueryChange { it.copy(mode = MatchMode.ANY) } },
                    label = { Text("Alguno") },
                )
                FilterChip(
                    selected = query.mode == MatchMode.ALL,
                    onClick = { onQueryChange { it.copy(mode = MatchMode.ALL) } },
                    label = { Text("Todos") },
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Limpiar") }
            }
        }
        if (query.mode == MatchMode.ALL && (query.numbers.size > 5 || query.stars.size > 2)) {
            item {
                Text(
                    "En cada sorteo salen 5 números y 2 estrellas: con «Todos» no puede haber más.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }

        // ---------- RESULTADOS ----------
        if (query.isEmpty) {
            item { Text("Elige fechas y/o números para buscar.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            item { SectionTitle("${results.size} sorteos encontrados") }
            items(results, key = { it.date.toString() }) { draw ->
                DrawCard(draw, highlightNumbers = query.numbers, highlightStars = query.stars)
            }
        }
    }

    picking?.let { field ->
        DatePickDialog(
            initial = if (field == DateField.FROM) query.from else query.to,
            onDismiss = { picking = null },
            onPick = { date ->
                onQueryChange {
                    if (field == DateField.FROM) it.copy(from = date) else it.copy(to = date)
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun DateButton(label: String, date: LocalDate?, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Filled.DateRange, contentDescription = null)
        Spacer(Modifier.padding(start = 6.dp))
        Text(if (date == null) label else "$label: ${date.format(shortFmt)}")
    }
}

/** Rejilla de bolas pulsables, 10 por fila. */
@Composable
private fun NumberGrid(range: IntRange, selected: Set<Int>, isStar: Boolean, onToggle: (Int) -> Unit) {
    val perRow = 10
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        range.toList().chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { n ->
                    val sel = n in selected
                    Ball(
                        value = n,
                        isStar = isStar,
                        highlighted = sel,
                        dimmed = !sel,
                        size = 32.dp,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .clickable { onToggle(n) },
                    )
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickDialog(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        yearRange = 2004..LocalDate.now().year,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let {
                    onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
                onDismiss()
            }) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    ) {
        DatePicker(state = state)
    }
}

private fun Set<Int>.toggle(n: Int): Set<Int> = if (n in this) this - n else this + n
