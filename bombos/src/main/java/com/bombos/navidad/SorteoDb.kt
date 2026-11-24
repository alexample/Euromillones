package com.bombos.navidad

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SorteoInfo(val id: Long, val creado: Long, val inicio: Long, val fase: String, val terminado: Boolean, val semilla: String, val extraidas: Int)

/** Sorteos simulados y todas sus bolas (número + premio, tabla, alambre y hora). */
class SorteoDb(ctx: Context) : SQLiteOpenHelper(ctx, "bombos.db", null, 1), Store {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sorteos (id INTEGER PRIMARY KEY AUTOINCREMENT, creado INTEGER NOT NULL, inicio INTEGER NOT NULL,
               fase TEXT NOT NULL DEFAULT 'LISTO', terminado INTEGER NOT NULL DEFAULT 0, semilla TEXT NOT NULL DEFAULT '')""",
        )
        db.execSQL(
            """CREATE TABLE bolas (sorteo INTEGER NOT NULL, orden INTEGER NOT NULL, tabla INTEGER NOT NULL, alambre INTEGER NOT NULL,
               pos INTEGER NOT NULL, numero INTEGER NOT NULL, premio TEXT NOT NULL, t INTEGER NOT NULL, PRIMARY KEY (sorteo, orden))""",
        )
        db.execSQL("CREATE INDEX bolas_num ON bolas (sorteo, numero)")
        db.execSQL("CREATE INDEX bolas_premio ON bolas (sorteo, premio)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun nuevo(semilla: String, inicio: Long): Long =
        writableDatabase.insert("sorteos", null, ContentValues().apply {
            put("creado", System.currentTimeMillis()); put("inicio", inicio); put("semilla", semilla)
        })

    override fun guardar(sorteo: Long, filas: List<Extraccion>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("INSERT OR REPLACE INTO bolas VALUES (?,?,?,?,?,?,?,?)")
            for (e in filas) {
                st.clearBindings()
                st.bindLong(1, sorteo); st.bindLong(2, e.orden.toLong()); st.bindLong(3, e.tabla.toLong())
                st.bindLong(4, e.alambre.toLong()); st.bindLong(5, e.pos.toLong()); st.bindLong(6, e.numero.toLong())
                st.bindString(7, e.cat.code); st.bindLong(8, e.t)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun fase(sorteo: Long, fase: String) {
        writableDatabase.execSQL("UPDATE sorteos SET fase = ? WHERE id = ?", arrayOf<Any>(fase, sorteo))
    }

    override fun terminar(sorteo: Long) {
        writableDatabase.execSQL("UPDATE sorteos SET terminado = 1 WHERE id = ?", arrayOf<Any>(sorteo))
    }

    private fun row(c: android.database.Cursor) = Extraccion(
        c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4), Cat.of(c.getString(5)) ?: Cat.PEDREA, c.getLong(6),
    )

    private val COLS = "orden, tabla, alambre, pos, numero, premio, t"

    override fun extracciones(sorteo: Long): List<Extraccion> =
        readableDatabase.rawQuery("SELECT $COLS FROM bolas WHERE sorteo = ? ORDER BY orden", arrayOf(sorteo.toString())).use { c ->
            val out = ArrayList<Extraccion>(c.count)
            while (c.moveToNext()) out += row(c)
            out
        }

    override fun enCurso(): Pair<Long, String>? =
        readableDatabase.rawQuery("SELECT id, fase FROM sorteos WHERE terminado = 0 ORDER BY id DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null
        }

    fun sorteos(): List<SorteoInfo> =
        readableDatabase.rawQuery(
            "SELECT s.id, s.creado, s.inicio, s.fase, s.terminado, s.semilla, (SELECT COUNT(*) FROM bolas b WHERE b.sorteo = s.id) " +
                "FROM sorteos s ORDER BY s.id DESC", null,
        ).use { c ->
            val out = ArrayList<SorteoInfo>()
            while (c.moveToNext()) out += SorteoInfo(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getInt(4) == 1, c.getString(5), c.getInt(6))
            out
        }

    fun borrar(sorteo: Long) {
        writableDatabase.delete("bolas", "sorteo = ?", arrayOf(sorteo.toString()))
        writableDatabase.delete("sorteos", "id = ?", arrayOf(sorteo.toString()))
    }

    /** Bolas cuyo número contiene [digits] (o es exactamente él si tiene 5 cifras). */
    fun buscarNumero(sorteo: Long, digits: String, limit: Int = 500): List<Extraccion> {
        val (w, a) = if (digits.length == 5) "numero = ?" to digits.toInt().toString()
        else "substr('0000' || numero, -5, 5) LIKE ?" to "%$digits%"
        return readableDatabase.rawQuery(
            "SELECT $COLS FROM bolas WHERE sorteo = ? AND $w ORDER BY orden LIMIT $limit", arrayOf(sorteo.toString(), a),
        ).use { c -> val out = ArrayList<Extraccion>(); while (c.moveToNext()) out += row(c); out }
    }

    fun porPremio(sorteo: Long, cat: Cat): List<Extraccion> =
        readableDatabase.rawQuery("SELECT $COLS FROM bolas WHERE sorteo = ? AND premio = ? ORDER BY orden", arrayOf(sorteo.toString(), cat.code))
            .use { c -> val out = ArrayList<Extraccion>(); while (c.moveToNext()) out += row(c); out }

    fun mayores(sorteo: Long): Mayores {
        val big = readableDatabase.rawQuery(
            "SELECT numero, premio FROM bolas WHERE sorteo = ? AND premio IN ('1','2','3','4') ORDER BY orden", arrayOf(sorteo.toString()),
        ).use { c -> val out = ArrayList<Pair<Int, String>>(); while (c.moveToNext()) out += c.getInt(0) to c.getString(1); out }
        return Mayores(
            big.firstOrNull { it.second == "1" }?.first, big.firstOrNull { it.second == "2" }?.first,
            big.firstOrNull { it.second == "3" }?.first, big.filter { it.second == "4" }.map { it.first },
        )
    }

    fun tabla(sorteo: Long, tabla: Int): List<Extraccion> =
        readableDatabase.rawQuery("SELECT $COLS FROM bolas WHERE sorteo = ? AND tabla = ? ORDER BY orden", arrayOf(sorteo.toString(), tabla.toString()))
            .use { c -> val out = ArrayList<Extraccion>(); while (c.moveToNext()) out += row(c); out }
}
