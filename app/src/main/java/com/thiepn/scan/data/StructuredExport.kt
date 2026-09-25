package com.thiepn.scan.data

import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class StructuredExportPage(
    val pageNumber: Int,
    val data: PageStructuredData
)

object StructuredDataExport {
    fun writeCsv(
        pages: List<StructuredExportPage>,
        destination: File
    ): File {
        destination.parentFile?.mkdirs()
        destination.bufferedWriter(Charsets.UTF_8).use { out ->
            out.write("\uFEFF")
            out.appendLine("KEY_VALUES")
            out.appendLine(
                csvRow(
                    listOf(
                        "Page",
                        "Key",
                        "Label",
                        "Value",
                        "Confidence",
                        "Reviewed",
                        "Source"
                    )
                )
            )
            pages.forEach { page ->
                page.data.keyValues.forEach { item ->
                    out.appendLine(
                        csvRow(
                            listOf(
                                page.pageNumber.toString(),
                                item.key,
                                item.label,
                                item.value,
                                percent(item.confidence),
                                item.reviewed.toString(),
                                item.source
                            )
                        )
                    )
                }
            }
            pages.forEach { page ->
                page.data.tables.forEach { table ->
                    out.appendLine()
                    out.appendLine(
                        csvRow(
                            listOf(
                                "TABLE",
                                "Page ${page.pageNumber}",
                                table.title,
                                "Confidence",
                                percent(table.confidence),
                                "Reviewed",
                                table.reviewed.toString()
                            )
                        )
                    )
                    for (row in 0 until table.rowCount) {
                        out.appendLine(
                            csvRow(
                                (0 until table.columnCount).map { col ->
                                    table.cell(row, col)?.text.orEmpty()
                                }
                            )
                        )
                    }
                }
            }
        }
        return destination
    }

    fun writeJson(
        documentId: String,
        title: String,
        pages: List<StructuredExportPage>,
        destination: File
    ): File {
        destination.parentFile?.mkdirs()
        destination.bufferedWriter(Charsets.UTF_8).use { out ->
            out.append("{\n")
            out.append("  \"version\": 1,\n")
            out.append("  \"documentId\": ")
                .append(json(documentId))
                .append(",\n")
            out.append("  \"title\": ")
                .append(json(title))
                .append(",\n")
            out.append("  \"pages\": [\n")

            pages.forEachIndexed { pageIndex, page ->
                out.append("    {\n")
                out.append("      \"page\": ")
                    .append(page.pageNumber.toString())
                    .append(",\n")
                out.append("      \"sourceToken\": ")
                    .append(json(page.data.sourceToken))
                    .append(",\n")
                out.append("      \"keyValues\": [")

                if (page.data.keyValues.isNotEmpty()) {
                    out.append("\n")
                }
                page.data.keyValues.forEachIndexed { index, item ->
                    out.append("        {")
                    out.append("\"id\":")
                        .append(json(item.id))
                        .append(",")
                    out.append("\"key\":")
                        .append(json(item.key))
                        .append(",")
                    out.append("\"label\":")
                        .append(json(item.label))
                        .append(",")
                    out.append("\"value\":")
                        .append(json(item.value))
                        .append(",")
                    out.append("\"confidence\":")
                        .append(item.confidence.toString())
                        .append(",")
                    out.append("\"reviewed\":")
                        .append(item.reviewed.toString())
                        .append(",")
                    out.append("\"source\":")
                        .append(json(item.source))
                        .append(",")
                    out.append("\"box\":")
                        .append(jsonBox(item.box))
                    out.append("}")
                    if (index < page.data.keyValues.lastIndex) {
                        out.append(",")
                    }
                    out.append("\n")
                }
                if (page.data.keyValues.isNotEmpty()) {
                    out.append("      ")
                }
                out.append("],\n")
                out.append("      \"tables\": [")

                if (page.data.tables.isNotEmpty()) {
                    out.append("\n")
                }
                page.data.tables.forEachIndexed { tableIndex, table ->
                    out.append("        {\n")
                    out.append("          \"id\": ")
                        .append(json(table.id))
                        .append(",\n")
                    out.append("          \"title\": ")
                        .append(json(table.title))
                        .append(",\n")
                    out.append("          \"rowCount\": ")
                        .append(table.rowCount.toString())
                        .append(",\n")
                    out.append("          \"columnCount\": ")
                        .append(table.columnCount.toString())
                        .append(",\n")
                    out.append("          \"confidence\": ")
                        .append(table.confidence.toString())
                        .append(",\n")
                    out.append("          \"reviewed\": ")
                        .append(table.reviewed.toString())
                        .append(",\n")
                    out.append("          \"source\": ")
                        .append(json(table.source))
                        .append(",\n")
                    out.append("          \"cells\": [")

                    val sortedCells = table.cells.sortedWith(
                        compareBy<StructuredCell> { it.row }
                            .thenBy { it.column }
                    )
                    if (sortedCells.isNotEmpty()) {
                        out.append("\n")
                    }
                    sortedCells.forEachIndexed { cellIndex, cell ->
                        out.append("            {")
                        out.append("\"row\":")
                            .append(cell.row.toString())
                            .append(",")
                        out.append("\"column\":")
                            .append(cell.column.toString())
                            .append(",")
                        out.append("\"text\":")
                            .append(json(cell.text))
                            .append(",")
                        out.append("\"confidence\":")
                            .append(cell.confidence.toString())
                            .append(",")
                        out.append("\"reviewed\":")
                            .append(cell.reviewed.toString())
                            .append(",")
                        out.append("\"box\":")
                            .append(jsonBox(cell.box))
                        out.append("}")
                        if (cellIndex < sortedCells.lastIndex) {
                            out.append(",")
                        }
                        out.append("\n")
                    }
                    if (sortedCells.isNotEmpty()) {
                        out.append("          ")
                    }
                    out.append("],\n")
                    out.append("          \"rows\": [\n")

                    for (row in 0 until table.rowCount) {
                        out.append("            [")
                        for (col in 0 until table.columnCount) {
                            if (col > 0) out.append(",")
                            out.append(
                                json(
                                    table.cell(
                                        row,
                                        col
                                    )?.text.orEmpty()
                                )
                            )
                        }
                        out.append("]")
                        if (row < table.rowCount - 1) {
                            out.append(",")
                        }
                        out.append("\n")
                    }

                    out.append("          ]\n")
                    out.append("        }")
                    if (tableIndex < page.data.tables.lastIndex) {
                        out.append(",")
                    }
                    out.append("\n")
                }
                if (page.data.tables.isNotEmpty()) {
                    out.append("      ")
                }
                out.append("]\n")
                out.append("    }")
                if (pageIndex < pages.lastIndex) {
                    out.append(",")
                }
                out.append("\n")
            }

            out.append("  ]\n")
            out.append("}\n")
        }
        return destination
    }

    fun writeXlsx(
        pages: List<StructuredExportPage>,
        destination: File
    ): File {
        destination.parentFile?.mkdirs()

        val baseNames = buildList {
            add("Key Values")
            pages.forEach { page ->
                page.data.tables.forEachIndexed { index, table ->
                    add(
                        "P${page.pageNumber} " +
                            table.title.ifBlank {
                                "Table ${index + 1}"
                            }
                    )
                }
            }
        }
        val uniqueNames = uniqueSheetNames(baseNames)
        val sheetCount = uniqueNames.size

        ZipOutputStream(
            destination.outputStream().buffered()
        ).use { zip ->
            add(
                zip,
                "[Content_Types].xml",
                contentTypes(sheetCount)
            )
            add(zip, "_rels/.rels", rootRels())
            add(
                zip,
                "docProps/app.xml",
                appProps(uniqueNames)
            )
            add(
                zip,
                "docProps/core.xml",
                coreProps()
            )
            add(
                zip,
                "xl/workbook.xml",
                workbook(uniqueNames)
            )
            add(
                zip,
                "xl/_rels/workbook.xml.rels",
                workbookRels(sheetCount)
            )
            add(zip, "xl/styles.xml", styles())

            writeKeyValuesWorksheet(
                zip = zip,
                path = "xl/worksheets/sheet1.xml",
                pages = pages
            )

            var sheetIndex = 2
            pages.forEach { page ->
                page.data.tables.forEach { table ->
                    writeTableWorksheet(
                        zip = zip,
                        path =
                            "xl/worksheets/sheet$sheetIndex.xml",
                        table = table
                    )
                    sheetIndex += 1
                }
            }
        }
        return destination
    }

    fun tableAsTsv(table: StructuredTable): String =
        (0 until table.rowCount).joinToString("\n") { row ->
            (0 until table.columnCount).joinToString("\t") { col ->
                table.cell(row, col)?.text.orEmpty()
            }
        }

    private fun writeKeyValuesWorksheet(
        zip: ZipOutputStream,
        path: String,
        pages: List<StructuredExportPage>
    ) {
        beginWorksheet(zip, path)
        var rowIndex = 1
        writeWorksheetRow(
            zip,
            rowIndex++,
            listOf(
                "Page",
                "Key",
                "Label",
                "Value",
                "Confidence",
                "Reviewed",
                "Source"
            )
        )
        pages.forEach { page ->
            page.data.keyValues.forEach { item ->
                writeWorksheetRow(
                    zip,
                    rowIndex++,
                    listOf(
                        page.pageNumber.toString(),
                        item.key,
                        item.label,
                        item.value,
                        percent(item.confidence),
                        if (item.reviewed) "Yes" else "No",
                        item.source
                    )
                )
            }
        }
        endWorksheet(zip)
    }

    private fun writeTableWorksheet(
        zip: ZipOutputStream,
        path: String,
        table: StructuredTable
    ) {
        beginWorksheet(zip, path)
        for (row in 0 until table.rowCount) {
            writeWorksheetRow(
                zip,
                row + 1,
                (0 until table.columnCount).map { col ->
                    table.cell(row, col)?.text.orEmpty()
                }
            )
        }
        endWorksheet(zip)
    }

    private fun beginWorksheet(
        zip: ZipOutputStream,
        path: String
    ) {
        zip.putNextEntry(ZipEntry(path))
        writeUtf8(
            zip,
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/" +
                "spreadsheetml/2006/main\"><sheetData>"
        )
    }

    private fun endWorksheet(zip: ZipOutputStream) {
        writeUtf8(zip, "</sheetData></worksheet>")
        zip.closeEntry()
    }

    private fun writeWorksheetRow(
        zip: ZipOutputStream,
        rowIndex: Int,
        values: List<String>
    ) {
        writeUtf8(
            zip,
            "<row r=\"$rowIndex\">"
        )
        values.forEachIndexed { column, value ->
            writeUtf8(
                zip,
                "<c r=\"${cellRef(column, rowIndex)}\" " +
                    "t=\"inlineStr\"><is><t " +
                    "xml:space=\"preserve\">"
            )
            writeUtf8(zip, xml(value))
            writeUtf8(zip, "</t></is></c>")
        }
        writeUtf8(zip, "</row>")
    }

    private fun writeUtf8(
        zip: ZipOutputStream,
        value: String
    ) {
        zip.write(value.toByteArray(Charsets.UTF_8))
    }

    private fun csvRow(values: List<String>) =
        values.joinToString(",") { value ->
            "\"" + value.replace("\"", "\"\"") + "\""
        }

    private fun percent(value: Float) =
        String.format(
            Locale.US,
            "%.1f%%",
            value * 100f
        )

    private fun jsonBox(box: StructuredBox?): String =
        box?.let {
            "{\"left\":" + it.left +
                ",\"top\":" + it.top +
                ",\"right\":" + it.right +
                ",\"bottom\":" + it.bottom + "}"
        } ?: "null"

    private fun json(value: String): String = buildString {
        append('\"')
        value.forEach { ch ->
            when (ch) {
                '\"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u")
                    append(
                        ch.code.toString(16)
                            .padStart(4, '0')
                    )
                } else {
                    append(ch)
                }
            }
        }
        append('\"')
    }

    private fun uniqueSheetNames(
        names: List<String>
    ): List<String> {
        val used = mutableSetOf<String>()
        return names.mapIndexed { index, raw ->
            val clean = raw
                .replace(
                    Regex("""[\\/*?:\[\]]"""),
                    " "
                )
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(31)
                .ifBlank {
                    "Sheet ${index + 1}"
                }
            var candidate = clean
            var suffix = 2
            while (candidate.lowercase() in used) {
                val tail = " ($suffix)"
                candidate =
                    clean.take(
                        (31 - tail.length)
                            .coerceAtLeast(1)
                    ) + tail
                suffix += 1
            }
            used += candidate.lowercase()
            candidate
        }
    }

    private fun add(
        zip: ZipOutputStream,
        path: String,
        text: String
    ) {
        zip.putNextEntry(ZipEntry(path))
        writeUtf8(zip, text)
        zip.closeEntry()
    }

    private fun xml(value: String): String {
        val clean = buildString {
            value.forEach { ch ->
                val code = ch.code
                if (
                    code == 0x9 ||
                    code == 0xA ||
                    code == 0xD ||
                    code in 0x20..0xD7FF ||
                    code in 0xE000..0xFFFD
                ) {
                    append(ch)
                }
            }
        }
        return clean
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun cellRef(
        column: Int,
        row: Int
    ): String {
        var number = column + 1
        val name = StringBuilder()
        while (number > 0) {
            val remainder = (number - 1) % 26
            name.insert(
                0,
                ('A'.code + remainder).toChar()
            )
            number = (number - 1) / 26
        }
        return name.toString() + row
    }

    private fun workbook(
        names: List<String>
    ): String = buildString {
        append(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" " +
                "standalone=\"yes\"?>"
        )
        append(
            "<workbook xmlns=\"http://schemas.openxmlformats.org/" +
                "spreadsheetml/2006/main\" xmlns:r=\"" +
                "http://schemas.openxmlformats.org/" +
                "officeDocument/2006/relationships\"><sheets>"
        )
        names.forEachIndexed { index, name ->
            append("<sheet name=\"")
                .append(xml(name))
                .append("\" sheetId=\"")
                .append(index + 1)
                .append("\" r:id=\"rId")
                .append(index + 1)
                .append("\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(count: Int): String =
        buildString {
            append(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" " +
                    "standalone=\"yes\"?>"
            )
            append(
                "<Relationships xmlns=\"" +
                    "http://schemas.openxmlformats.org/package/2006/" +
                    "relationships\">"
            )
            for (index in 1..count) {
                append("<Relationship Id=\"rId")
                    .append(index)
                    .append(
                        "\" Type=\"http://schemas.openxmlformats.org/" +
                            "officeDocument/2006/relationships/worksheet\" " +
                            "Target=\"worksheets/sheet"
                    )
                    .append(index)
                    .append(".xml\"/>")
            }
            append("<Relationship Id=\"rId")
                .append(count + 1)
                .append(
                    "\" Type=\"http://schemas.openxmlformats.org/" +
                        "officeDocument/2006/relationships/styles\" " +
                        "Target=\"styles.xml\"/>"
                )
            append("</Relationships>")
        }

    private fun contentTypes(count: Int): String =
        buildString {
            append(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" " +
                    "standalone=\"yes\"?>"
            )
            append(
                "<Types xmlns=\"http://schemas.openxmlformats.org/" +
                    "package/2006/content-types\">"
            )
            append(
                "<Default Extension=\"rels\" ContentType=\"" +
                    "application/vnd.openxmlformats-package." +
                    "relationships+xml\"/>"
            )
            append(
                "<Default Extension=\"xml\" " +
                    "ContentType=\"application/xml\"/>"
            )
            append(
                "<Override PartName=\"/xl/workbook.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-" +
                    "officedocument.spreadsheetml.sheet.main+xml\"/>"
            )
            append(
                "<Override PartName=\"/xl/styles.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-" +
                    "officedocument.spreadsheetml.styles+xml\"/>"
            )
            for (index in 1..count) {
                append(
                    "<Override PartName=\"/xl/worksheets/sheet"
                )
                    .append(index)
                    .append(
                        ".xml\" ContentType=\"application/vnd." +
                            "openxmlformats-officedocument." +
                            "spreadsheetml.worksheet+xml\"/>"
                    )
            }
            append(
                "<Override PartName=\"/docProps/core.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-" +
                    "package.core-properties+xml\"/>"
            )
            append(
                "<Override PartName=\"/docProps/app.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-" +
                    "officedocument.extended-properties+xml\"/>"
            )
            append("</Types>")
        }

    private fun rootRels() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""

    private fun styles() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border/></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
</styleSheet>"""

    private fun coreProps() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:creator>Scan</dc:creator><cp:lastModifiedBy>Scan</cp:lastModifiedBy>
</cp:coreProperties>"""

    private fun appProps(
        names: List<String>
    ): String = buildString {
        append(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" " +
                "standalone=\"yes\"?>"
        )
        append(
            "<Properties xmlns=\"http://schemas.openxmlformats.org/" +
                "officeDocument/2006/extended-properties\" " +
                "xmlns:vt=\"http://schemas.openxmlformats.org/" +
                "officeDocument/2006/docPropsVTypes\">" +
                "<Application>Scan</Application>"
        )
        append("<TitlesOfParts><vt:vector size=\"")
            .append(names.size)
            .append("\" baseType=\"lpstr\">")
        names.forEach { name ->
            append("<vt:lpstr>")
                .append(xml(name))
                .append("</vt:lpstr>")
        }
        append(
            "</vt:vector></TitlesOfParts></Properties>"
        )
    }
}
