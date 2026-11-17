package com.euromillones.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.euromillones.app.ui.screens.MasksScreen
import com.euromillones.app.ui.screens.ResultsScreen
import com.euromillones.app.ui.screens.SearchScreen
import com.euromillones.app.ui.theme.EuroTheme

/**
 * MÓDULOS DE LA APP.
 * Para añadir uno nuevo: añade una entrada aquí y su pantalla en el `when` de AppRoot.
 */
enum class AppModule(val title: String, val icon: ImageVector) {
    RESULTADOS("Resultados", Icons.Filled.Home),
    BUSCAR("Buscar", Icons.Filled.Search),
    MASCARAS("Máscaras", Icons.Filled.Face),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EuroTheme { AppRoot() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var module by rememberSaveable { mutableStateOf(AppModule.RESULTADOS) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Euromillones · ${module.title}") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    actions = {
                        IconButton(onClick = vm::refresh, enabled = !state.loading) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Actualizar")
                        }
                    },
                )
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.progress?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                AppModule.entries.forEach { m ->
                    NavigationBarItem(
                        selected = m == module,
                        onClick = { module = m },
                        icon = { Icon(m.icon, contentDescription = m.title) },
                        label = { Text(m.title) },
                    )
                }
            }
        },
    ) { padding ->
        val mod = Modifier
            .fillMaxSize()
            .padding(padding)
        when (module) {
            AppModule.RESULTADOS -> ResultsScreen(state, mod)
            AppModule.BUSCAR -> SearchScreen(
                draws = state.draws,
                query = query,
                onQueryChange = vm::updateQuery,
                onClear = vm::clearQuery,
                modifier = mod,
            )
            AppModule.MASCARAS -> MasksScreen(state.draws, mod)
        }
    }
}
