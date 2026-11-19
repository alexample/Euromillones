package com.euromillones.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import com.euromillones.app.data.SolarEntropy

/** Línea con el origen del azar: hash solar actual, hora del dato y si se está usando el respaldo. */
@Composable
fun EntropyInfo(modifier: Modifier = Modifier) {
    val st by SolarEntropy.status.collectAsState()
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    Column(modifier) {
        val seed = st.seed
        if (seed == null) {
            Text(
                "Azar: esperando datos del Sol (NOAA)… mientras, se usa el azar seguro del móvil." +
                    (st.error?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            val mins = ((now - seed.fetchedAt) / 60_000).coerceAtLeast(0)
            Text(
                "Azar: ${SolarEntropy.SOURCE_NAME}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Hash SHA-256: ${seed.hex.take(16)}…${seed.hex.takeLast(8)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Dato solar de las ${seed.dataTime?.substring(11, 16) ?: "?"} UTC · descargado hace " +
                    (if (mins == 0L) "menos de 1 min" else "$mins min") + " · se renueva cada 5 min" +
                    (st.error?.let { " · último intento falló: $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row {
            TextButton(onClick = { SolarEntropy.refreshNow() }) { Text("Actualizar ahora", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SolarEntropy.SOURCE_URL)))
                }
            }) { Text("Ver datos", style = MaterialTheme.typography.labelSmall) }
        }
    }
}
