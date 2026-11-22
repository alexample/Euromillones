package com.geoloteria.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Base de datos SQLite con TODOS los números generados o importados (sin límite).
 * Índices por número, población y comunidad para que la búsqueda sea instantánea.
 */
/** Columnas (o expresiones) por las que se puede hacer ranking. */
const val YEAR_EXPR = "CAST(substr(date, 1, length(date) - 6) AS INTEGER)"
val RANK_COLUMNS = setOf("nuc", "number", "region_key", "word", "date", "event", YEAR_EXPR)

class DrawDb(context: Context) : SQLiteOpenHelper(context, "geoloteria.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE draws (
                id INTEGER PRIMARY KEY,
                number TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                nuc INTEGER NOT NULL,
                dist REAL NOT NULL,
                radius REAL NOT NULL,
                hit INTEGER NOT NULL,
                ts INTEGER NOT NULL,
                town_key TEXT NOT NULL,
                region_key TEXT NOT NULL,
                word TEXT NOT NULL DEFAULT '',
                date TEXT NOT NULL DEFAULT '',
                event INTEGER NOT NULL DEFAULT -1,
                euro TEXT NOT NULL DEFAULT '',
                euro_hits INTEGER NOT NULL DEFAULT -1,
                euro_info TEXT NOT NULL DEFAULT ''
            )""",
        )
        db.execSQL("CREATE INDEX idx_number ON draws(number)")
        db.execSQL("CREATE INDEX idx_town ON draws(town_key)")
        db.execSQL("CREATE INDEX idx_hit ON draws(hit, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE draws ADD COLUMN word TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE draws ADD COLUMN date TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) db.execSQL("ALTER TABLE draws ADD COLUMN event INTEGER NOT NULL DEFAULT -1")
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE draws ADD COLUMN euro TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE draws ADD COLUMN euro_hits INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE draws ADD COLUMN euro_info TEXT NOT NULL DEFAULT ''")
        }
    }

    /** Guarda un lote de números en una sola transacción (rápido aunque sean miles). */
    fun insertAll(items: List<Pair<Draw, Long>>, index: Map<Nucleo, Int>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT OR REPLACE INTO draws (id, number, lat, lon, nuc, dist, radius, hit, ts, town_key, region_key, word, date, event, euro, euro_hits, euro_info) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            )
            for ((d, ts) in items) {
                st.clearBindings()
                st.bindLong(1, d.id)
                st.bindString(2, d.number)
                st.bindDouble(3, d.lat)
                st.bindDouble(4, d.lon)
                st.bindLong(5, (index[d.nearest] ?: 0).toLong())
                st.bindDouble(6, d.distanceKm)
                st.bindDouble(7, d.radiusKm)
                st.bindLong(8, if (d.isHit) 1 else 0)
                st.bindLong(9, ts)
                st.bindString(10, d.nearest.key)
                st.bindString(11, d.nearest.regionKey)
                st.bindString(12, d.word)
                st.bindString(13, d.date)
                st.bindLong(14, d.event.toLong())
                st.bindString(15, d.euro)
                st.bindLong(16, d.euroHits.toLong())
                st.bindString(17, d.euroInfo)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun count(hitsOnly: Boolean = false): Long =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM draws" + if (hitsOnly) " WHERE hit = 1" else "", null,
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

    /** Números cuya combinación de Euromillones acertó al menos [k] en algún sorteo real. */
    fun countEuro(k: Int): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM draws WHERE euro_hits >= $k", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

    fun maxId(): Long =
        readableDatabase.rawQuery("SELECT IFNULL(MAX(id), 0) FROM draws", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM draws")
    }

    private fun where(query: String, hitsOnly: Boolean): Pair<String, Array<String>> {
        val q = query.trim()
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        if (hitsOnly) parts += "hit = 1"
        val dmy = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})""").matchEntire(q)
        val year = Regex("""a[nñ]o\s*(\d{4})""").matchEntire(norm(q).replace("ano", "año"))
            ?: Regex("""a[nñ]o\s*(\d{4})""").matchEntire(q.lowercase())
        val ev = Regex("""evento\s*(\d+)""").matchEntire(q.lowercase())
        val ac = Regex("""(\d)\s*aciertos?""").matchEntire(q.lowercase())
        if (q.isNotEmpty()) {
            if (ac != null) {
                parts += "euro_hits >= ?"
                args += ac.groupValues[1]
            } else if (ev != null) {
                parts += "event = ?"
                args += ev.groupValues[1]
            } else if (dmy != null) {
                val (dd, mm, yy) = dmy.destructured
                parts += "date = ?"
                args += "%s-%02d-%02d".format(yy, mm.toInt(), dd.toInt())
            } else if (year != null) {
                parts += "$YEAR_EXPR = ?"
                args += year.groupValues[1].toInt().toString()
            } else if (q.all { it.isDigit() }) {
                parts += "number LIKE ?"
                args += "%$q%"
            } else {
                val k = norm(q)
                parts += "(town_key LIKE ? OR region_key LIKE ? OR word LIKE ?)"
                args += "%$k%"
                args += "%$k%"
                args += "%${q.lowercase()}%"
            }
        }
        val w = if (parts.isEmpty()) "" else " WHERE " + parts.joinToString(" AND ")
        return w to args.toTypedArray()
    }

    /** Cuántos números tienen palabra/fecha (los anteriores a esa versión no). */
    fun countWith(column: String, hitsOnly: Boolean): Long {
        require(column in RANK_COLUMNS)
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM draws" + rankWhere(column, hitsOnly), null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
    }

    private fun rankWhere(column: String, hitsOnly: Boolean): String {
        val parts = ArrayList<String>()
        if (hitsOnly) parts += "hit = 1"
        if (column == "word") parts += "word <> ''"
        if (column.contains("date")) parts += "date <> ''"
        if (column == "event" || column == YEAR_EXPR) parts += "event >= 0"
        return if (parts.isEmpty()) "" else " WHERE " + parts.joinToString(" AND ")
    }

    /** Cuántos coinciden con la búsqueda (en TODO lo guardado). */
    fun searchCount(query: String, hitsOnly: Boolean = false): Long {
        val (w, a) = where(query, hitsOnly)
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM draws$w", a)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
    }

    /** Una página de resultados, del más reciente al más antiguo. */
    fun search(
        query: String,
        nucleos: List<Nucleo>,
        hitsOnly: Boolean = false,
        limit: Int = 500,
        offset: Int = 0,
    ): List<Pair<Draw, Long>> {
        val (w, a) = where(query, hitsOnly)
        val out = ArrayList<Pair<Draw, Long>>()
        readableDatabase.rawQuery(
            "SELECT id, number, lat, lon, nuc, dist, radius, ts, word, date, event, euro, euro_hits, euro_info FROM draws$w ORDER BY id DESC LIMIT $limit OFFSET $offset",
            a,
        ).use { c ->
            while (c.moveToNext()) {
                val n = nucleos.getOrNull(c.getInt(4)) ?: continue
                out += Draw(c.getLong(0), c.getString(1), c.getDouble(2), c.getDouble(3), n, c.getDouble(5), c.getDouble(6),
                    c.getString(8) ?: "", c.getString(9) ?: "", c.getInt(10), c.getString(11) ?: "", c.getInt(12), c.getString(13) ?: "") to
                    c.getLong(7)
            }
        }
        return out
    }

    /** Recorre todo (para exportar) sin cargarlo entero en memoria. */
    fun forEach(nucleos: List<Nucleo>, hitsOnly: Boolean, max: Int, block: (Draw, Long) -> Unit) {
        val w = if (hitsOnly) " WHERE hit = 1" else ""
        readableDatabase.rawQuery(
            "SELECT id, number, lat, lon, nuc, dist, radius, ts, word, date, event, euro, euro_hits, euro_info FROM draws$w ORDER BY id DESC LIMIT $max", null,
        ).use { c ->
            while (c.moveToNext()) {
                val n = nucleos.getOrNull(c.getInt(4)) ?: continue
                block(Draw(c.getLong(0), c.getString(1), c.getDouble(2), c.getDouble(3), n, c.getDouble(5), c.getDouble(6),
                    c.getString(8) ?: "", c.getString(9) ?: "", c.getInt(10), c.getString(11) ?: "", c.getInt(12), c.getString(13) ?: ""), c.getLong(7))
            }
        }
    }

    /** Ranking: devuelve (valor, veces) de más a menos; a igualdad de veces, el que salió más recientemente primero. */
    fun top(column: String, hitsOnly: Boolean, limit: Int): List<Pair<String, Long>> {
        require(column in RANK_COLUMNS)
        val w = rankWhere(column, hitsOnly)
        val out = ArrayList<Pair<String, Long>>()
        readableDatabase.rawQuery(
            "SELECT $column, COUNT(*) AS c, MAX(id) AS last FROM draws$w GROUP BY $column ORDER BY c DESC, last DESC LIMIT $limit", null,
        ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getLong(1) }
        return out
    }

    /** Cuántos valores distintos hay (ciudades, números o comunidades que han salido al menos una vez). */
    fun distinct(column: String, hitsOnly: Boolean): Long {
        require(column in RANK_COLUMNS)
        val w = rankWhere(column, hitsOnly)
        return readableDatabase.rawQuery("SELECT COUNT(DISTINCT $column) FROM draws$w", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
    }
}
