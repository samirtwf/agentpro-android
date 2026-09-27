package org.linphone.ui.main.campaign

import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Tiny zero-dependency .xlsx (OOXML) reader/writer used for Campaign import/export.
 *
 * Apache POI is intentionally avoided: it is large and unreliable on Android. An .xlsx file is
 * just a ZIP of XML parts, which [java.util.zip] + the platform [XmlPullParser] handle natively.
 *
 * The writer emits a single sheet using inline strings, and applies the built-in text number
 * format (numFmtId 49) to the requested columns so phone numbers keep their leading zeros when
 * the user edits the file in Excel. The reader returns the first worksheet as rows of strings
 * (handling shared strings, inline strings and numbers).
 */
object XlsxUtils {

    // ---------------------------- Writer ----------------------------

    fun write(
        file: File,
        headers: List<String>,
        rows: List<List<String>>,
        textColumns: Set<Int> = emptySet(),
        sheetName: String = "Contacts"
    ) {
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            entry(zos, "[Content_Types].xml", CONTENT_TYPES)
            entry(zos, "_rels/.rels", ROOT_RELS)
            entry(zos, "xl/workbook.xml", workbookXml(sheetName))
            entry(zos, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            entry(zos, "xl/styles.xml", STYLES)
            entry(zos, "xl/worksheets/sheet1.xml", sheetXml(headers, rows, textColumns))
        }
    }

    private fun entry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun sheetXml(headers: List<String>, rows: List<List<String>>, textColumns: Set<Int>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        if (textColumns.isNotEmpty()) {
            sb.append("<cols>")
            for (c in textColumns.sorted()) {
                val col = c + 1
                sb.append("<col min=\"$col\" max=\"$col\" width=\"20\" style=\"1\" customWidth=\"1\"/>")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        var r = 1
        sb.append(rowXml(r, headers, textColumns))
        r++
        for (row in rows) {
            sb.append(rowXml(r, row, textColumns))
            r++
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun rowXml(rowNum: Int, values: List<String>, textColumns: Set<Int>): String {
        val sb = StringBuilder("<row r=\"$rowNum\">")
        values.forEachIndexed { i, v ->
            val ref = colName(i) + rowNum
            val s = if (textColumns.contains(i)) " s=\"1\"" else ""
            if (v.isEmpty()) {
                sb.append("<c r=\"$ref\"$s/>")
            } else {
                sb.append("<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(escape(v)).append("</t></is></c>")
            }
        }
        sb.append("</row>")
        return sb.toString()
    }

    private fun colName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, 'A' + (i % 26))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    private fun escape(s: String): String = buildString {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') append(' ') else append(ch)
        }
    }

    // ---------------------------- Reader ----------------------------

    fun read(input: InputStream): List<List<String>> {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) entries[e.name] = zis.readBytes()
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        val shared = entries["xl/sharedStrings.xml"]
            ?.let { parseSharedStrings(ByteArrayInputStream(it)) } ?: emptyList()
        val sheetName = entries.keys
            .filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            .minOrNull() ?: return emptyList()
        return parseSheet(ByteArrayInputStream(entries[sheetName]!!), shared)
    }

    private fun newParser(input: InputStream): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, "UTF-8")
        return p
    }

    private fun parseSharedStrings(input: InputStream): List<String> {
        val list = ArrayList<String>()
        val p = newParser(input)
        var event = p.eventType
        var inSi = false
        var inT = false
        val current = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { inSi = true; current.setLength(0) }
                    "t" -> if (inSi) inT = true
                }
                XmlPullParser.TEXT -> if (inT) current.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "si" -> { list.add(current.toString()); inSi = false }
                }
            }
            event = p.next()
        }
        return list
    }

    private fun parseSheet(input: InputStream, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val p = newParser(input)
        var event = p.eventType
        var cells = HashMap<Int, String>()
        var maxCol = -1
        var curCol = -1
        var curType = ""
        var inV = false
        var inT = false
        val value = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> { cells = HashMap(); maxCol = -1 }
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        curCol = if (ref != null) colIndex(ref) else curCol + 1
                        curType = p.getAttributeValue(null, "t") ?: ""
                        value.setLength(0)
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        var text = value.toString()
                        if (curType == "s") {
                            val idx = text.trim().toIntOrNull()
                            text = if (idx != null && idx in shared.indices) shared[idx] else ""
                        }
                        if (text.isNotEmpty()) {
                            cells[curCol] = text
                            if (curCol > maxCol) maxCol = curCol
                        }
                    }
                    "row" -> {
                        val list = ArrayList<String>(maxCol + 1)
                        for (i in 0..maxCol) list.add(cells[i] ?: "")
                        rows.add(list)
                    }
                }
            }
            event = p.next()
        }
        return rows
    }

    private fun colIndex(ref: String): Int {
        var idx = 0
        for (ch in ref) {
            val up = ch.uppercaseChar()
            if (up in 'A'..'Z') idx = idx * 26 + (up - 'A' + 1) else break
        }
        return (idx - 1).coerceAtLeast(0)
    }

    // ---------------------------- Static parts ----------------------------

    private const val CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "</Types>"

    private const val ROOT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

    private const val WORKBOOK_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>"

    private const val STYLES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>" +
            "<borders count=\"1\"><border/></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"2\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "</cellXfs>" +
            "</styleSheet>"

    private fun workbookXml(sheetName: String): String {
        val safe = escape(sheetName).take(31)
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"$safe\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
            "</workbook>"
    }
}
