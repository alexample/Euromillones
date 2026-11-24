package com.bombos.navidad

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Lector / escritor mínimo de Excel (.xlsx) sin librerías externas.
 * Escribe texto en línea (inlineStr) y números; lee también ficheros guardados por Excel
 * (textos compartidos en sharedStrings.xml).
 */
object Xlsx {

    // ---------------- Escritura ----------------

    /** Hoja que se escribe fila a fila: [rows] recibe una función a la que pasar cada fila. */
    class Sheet(val name: String, val rows: (emit: (List<Any?>) -> Unit) -> Unit)

    /** Comodidad: hoja a partir de una lista ya hecha. */
    fun sheet(name: String, list: List<List<Any?>>) = Sheet(name) { emit -> list.forEach(emit) }

    /** Excel admite como mucho 1.048.576 filas por hoja. */
    const val MAX_ROWS = 1_048_576

    fun write(out: OutputStream, sheets: List<Sheet>) {
        val zip = ZipOutputStream(out.buffered())
        fun put(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val n = sheets.size
        put(
            "[Content_Types].xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                """<Default Extension="xml" ContentType="application/xml"/>""" +
                """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                (1..n).joinToString("") {
                    """<Override PartName="/xl/worksheets/sheet$it.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
                } +
                "</Types>",
        )
        put(
            "_rels/.rels",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
                "</Relationships>",
        )
        put(
            "xl/workbook.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                sheets.mapIndexed { i, sh ->
                    """<sheet name="${esc(sh.name.take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>"""
                }.joinToString("") +
                "</sheets></workbook>",
        )
        put(
            "xl/_rels/workbook.xml.rels",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                (1..n).joinToString("") {
                    """<Relationship Id="rId$it" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$it.xml"/>"""
                } +
                "</Relationships>",
        )
        sheets.forEachIndexed { i, sh ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet${i + 1}.xml"))
            val w = zip.bufferedWriter(Charsets.UTF_8)
            w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            w.write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            var r = 0
            sh.rows { row ->
                if (r < MAX_ROWS) {
                    r++
                    w.write("""<row r="$r">""")
                    row.forEachIndexed { c, v ->
                        val ref = colName(c) + r
                        when (v) {
                            null -> {}
                            is Number -> w.write("""<c r="$ref"><v>$v</v></c>""")
                            else -> w.write("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>""")
                        }
                    }
                    w.write("</row>")
                }
            }
            w.write("</sheetData></worksheet>")
            w.flush() // no se cierra: cerraría el zip entero
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val m = (n - 1) % 26
            sb.insert(0, ('A' + m))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---------------- Lectura ----------------

    /** Devuelve todas las hojas (en orden de fichero) como filas de celdas de texto. */
    fun read(input: InputStream): List<List<List<String>>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) {
                    files[e.name] = zip.readBytes()
                }
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { parseShared(it) } ?: emptyList()
        return files.keys.filter { it.startsWith("xl/worksheets/sheet") }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            .map { parseSheet(files.getValue(it), shared) }
    }

    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(bytes), "UTF-8")
    }

    private fun parseShared(bytes: ByteArray): List<String> {
        val p = parser(bytes)
        val out = ArrayList<String>()
        var cur: StringBuilder? = null
        var inT = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> cur = StringBuilder()
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inT) cur?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "si" -> { out += cur?.toString().orEmpty(); cur = null }
                }
            }
        }
        return out
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val p = parser(bytes)
        val rows = ArrayList<List<String>>()
        var row: MutableMap<Int, String>? = null
        var col = 0
        var type: String? = null
        var value = StringBuilder()
        var inValue = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = HashMap()
                    "c" -> {
                        col = colIndex(p.getAttributeValue(null, "r") ?: "") ?: (row?.keys?.maxOrNull()?.plus(1) ?: 0)
                        type = p.getAttributeValue(null, "t")
                        value = StringBuilder()
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = value.toString()
                        val text = if (type == "s") shared.getOrElse(raw.trim().toIntOrNull() ?: -1) { "" } else raw
                        row?.put(col, text)
                    }
                    "row" -> {
                        val r = row ?: HashMap()
                        val max = r.keys.maxOrNull() ?: -1
                        rows += (0..max).map { r[it].orEmpty() }
                        row = null
                    }
                }
            }
        }
        return rows
    }

    private fun colIndex(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return null
        return letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
    }
}
