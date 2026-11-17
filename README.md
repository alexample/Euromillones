# Euromillones – App Android

App que descarga todos los resultados de Euromillones (desde 2004), los guarda en el
móvil para usarlos sin conexión y permite buscarlos.

## Módulos actuales
| Módulo | Qué hace |
|---|---|
| **Resultados** | Último sorteo destacado + histórico completo |
| **Buscar** | Filtro por rango de fechas (o un día concreto) y por números/estrellas, en modo **Alguno** (sale al menos uno) o **Todos** (salen todos en el mismo sorteo). Los aciertos se resaltan en verde. |

## Origen de los datos
Base de datos propia: `data/draws.json` en este repositorio (todos los sorteos desde 2004).
La tarea **Actualizar sorteos** (`.github/workflows/actualizar-sorteos.yml`) la rellena
los martes y viernes por la noche, tras cada sorteo, leyendo el histórico de euro-millions.com
(`scripts/update_draws.py`). Se puede lanzar a mano desde *Actions → Actualizar sorteos → Run workflow*.

La app descarga ese fichero y lo guarda en el móvil, así funciona también sin conexión.
Botón ⟳ arriba a la derecha para actualizar a mano.

**APK directo:** https://github.com/alexample/Euromillones/releases/download/ultima/Euromillones.apk

## Cómo obtener el APK

### Opción A – Sin instalar nada (GitHub)
1. Crea un repositorio en GitHub y sube el contenido de esta carpeta.
2. Ve a la pestaña **Actions** → «Compilar APK» se ejecuta solo (o pulsa *Run workflow*).
3. Al terminar (~5 min) descarga el artefacto **euromillones-apk** → dentro está `app-debug.apk`.
4. Pásalo al móvil e instálalo (acepta «instalar apps de origen desconocido»).

### Opción B – Android Studio
1. *File → Open* y elige esta carpeta.
2. Espera a que sincronice Gradle.
3. *Build → Build App Bundle(s)/APK(s) → Build APK(s)*.
   El APK queda en `app/build/outputs/apk/debug/`.

## Añadir un módulo nuevo
1. Crea la pantalla en `app/src/main/java/com/euromillones/app/ui/screens/MiModuloScreen.kt`.
2. Añade una entrada al `enum class AppModule` en `MainActivity.kt`.
3. Añade su caso en el `when (module)` de `AppRoot`.

Todas las pantallas reciben `state.draws` (lista completa de sorteos), así que
estadísticas, comprobador de boletos, etc. no necesitan más conexión.

## Estructura
```
app/src/main/java/com/euromillones/app/
├── MainActivity.kt        ← navegación y lista de módulos
├── MainViewModel.kt       ← estado: sorteos, carga, búsqueda
├── data/
│   ├── Draw.kt            ← modelo + lógica de búsqueda
│   └── DrawRepository.kt  ← API + caché local
└── ui/
    ├── components/DrawCard.kt   ← bolas y tarjeta de sorteo
    ├── screens/ResultsScreen.kt
    ├── screens/SearchScreen.kt
    └── theme/Theme.kt
```
