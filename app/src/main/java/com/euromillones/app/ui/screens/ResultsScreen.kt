package com.euromillones.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.euromillones.app.UiState
import com.euromillones.app.ui.components.DrawCard

@Composable
fun ResultsScreen(state: UiState, modifier: Modifier = Modifier) {
    if (state.draws.isEmpty()) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                state.error ?: if (state.loading) "Descargando el histórico de sorteos…\nSolo tarda la primera vez." else "Sin datos todavía.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.error?.let { err ->
            item { Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        item {
            Text("Último sorteo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        item { DrawCard(state.draws.first(), large = true) }
        item {
            Text(
                "Histórico · ${state.draws.size} sorteos",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(state.draws.drop(1), key = { it.date.toString() }) { draw ->
            DrawCard(draw)
        }
    }
}
