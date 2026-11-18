package com.euromillones.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.euromillones.app.data.DEFAULT_HOT_WINDOW
import com.euromillones.app.data.DEFAULT_NAVIDAD_WINDOW
import com.euromillones.app.data.GenTarget
import com.euromillones.app.ui.screens.GeneratorScreen
import com.euromillones.app.ui.screens.MasksScreen
import com.euromillones.app.ui.screens.NavidadGeneratorScreen
import com.euromillones.app.ui.screens.NavidadScreen
import com.euromillones.app.ui.screens.ResultsScreen
import com.euromillones.app.ui.screens.SearchScreen
import com.euromillones.app.ui.screens.StatsScreen
import com.euromillones.app.ui.theme.EuroTheme

/**
 * MÓDULOS DE LA APP.
 * Para añadir uno nuevo: añade una entrada aquí y su pantalla en el `when` de AppRoot.
 */
enum class AppModule(val title: String, val icon: ImageVector) {
    RESULTADOS("Resultados", Icons.Filled.Home),
    BUSCAR("Buscar", Icons.Filled.Search),
    MASCARAS("Máscaras", Icons.Filled.Face),
    ESTADISTICAS("Estadística", Icons.Filled.Info),
    NAVIDAD("Navidad", Icons.Filled.Star),
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
    // Pantalla de generador abierta (máscara codificada) o null
    var gen by rememberSaveable { mutableStateOf<String?>(null) }
    var hotWindow by rememberSaveable { mutableIntStateOf(DEFAULT_HOT_WINDOW) }
    val gordos by vm.gordos.collectAsStateWithLifecycle()
    var navWindow by rememberSaveable { mutableIntStateOf(DEFAULT_NAVIDAD_WINDOW) }
    // Generador de Navidad abierto ("LMH|CTF") o null
    var navGen by rememberSaveable { mutableStateOf<String?>(null) }
    val openMask: (GenTarget) -> Unit = { gen = it.encode() }
    BackHandler(enabled = gen != null || navGen != null) { gen = null; navGen = null }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                gen != null -> "Generar combinaciones"
                                navGen != null -> "Generar números de Navidad"
                                module == AppModule.NAVIDAD -> "Lotería de Navidad"
                                else -> "Euromillones · ${module.title}"
                            },
                        )
                    },
                    navigationIcon = {
                        if (gen != null || navGen != null) {
                            IconButton(onClick = { gen = null; navGen = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
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
                        onClick = { module = m; gen = null; navGen = null },
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
        val g = gen
        val ng = navGen
        if (g != null) {
            GeneratorScreen(GenTarget.decode(g), state.draws, hotWindow, mod)
        } else if (ng != null) {
            NavidadGeneratorScreen(ng.substringBefore("|"), ng.substringAfter("|"), gordos, navWindow, mod)
        } else when (module) {
            AppModule.RESULTADOS -> ResultsScreen(state, mod)
            AppModule.BUSCAR -> SearchScreen(
                draws = state.draws,
                query = query,
                onQueryChange = vm::updateQuery,
                onClear = vm::clearQuery,
                modifier = mod,
            )
            AppModule.MASCARAS -> MasksScreen(state.draws, hotWindow, { hotWindow = it }, openMask, mod)
            AppModule.ESTADISTICAS -> StatsScreen(state.draws, openMask, mod)
            AppModule.NAVIDAD -> NavidadScreen(
                gordos = gordos,
                window = navWindow,
                onWindowChange = { navWindow = it },
                onOpen = { l, c -> navGen = "$l|$c" },
                modifier = mod,
            )
        }
    }
}
