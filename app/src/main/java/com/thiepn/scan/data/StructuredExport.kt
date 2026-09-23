package com.thiepn.scan.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class StructuredExportPage(
    val pageNumber:Int,
    val data:PageStructuredData
)

object StructuredDataExport {
    fun writeCsv(pages:List<StructuredExportPage>,destination:File):File {
        destination.parentFile?.mkdirs()
        destination.bufferedWriter().use { out ->
            out.appendLine("KEY_VALUES")
            out.appendLine(csvRow(listOf(
                "Page","Key","Label","Value","Confidence","Reviewed","Source"
            )))
            pages.forEach { page ->
                page.data.keyValues.forEach { item ->
                    out.appendLine(csvRow(listOf(
                        page.pageNumber.toString(),item.key,item.label,item.value,
                        percent(item.confidence),item.reviewed.toString(),item.source
                    )))
                }
            }
            pages.forEach { page ->
                page.data.tables.forEach { table ->
                    out.appendLine()
                    out.appendLine(csvRow(listOf(
                        "TABLE","Page ${page.pageNumber}",table.title,
                        "Confidence",percent(table.confidence),
                        "Reviewed",table.reviewed.toString()
                    )))
                    for(row in 0 until table.rowCount) {
                        out.appendLine(csvRow((0 until table.columnCount).map { col ->
                            table.cell(row,col)?.text.orEmpty()
                        }))
                    }
                }
            }
        }
        return destination
    }

    fun writeJson(
        documentId:String,
        title:String,
        pages:List<StructuredExportPage>,
        destination:File
    ):File {
        destination.parentFile?.mkdirs()
        destination.writeText(buildString {
            append("{\n")
            append("  \"version\": 1,\n")
            append("  \"documentId\": ").append(json(documentId)).append(",\n")
            append("  \"title\": ").append(json(title)).append(",\n")
            append("  \"pages\": [\n")
            pages.forEachIndexed { pi,page ->
                append("    {\n")
                append("      \"page\": ").append(page.pageNumber).append(",\n")
                append("      \"sourceToken\": ").append(json(page.data.sourceToken)).append(",\n")
                append("      \"keyValues\": [")
                if(page.data.keyValues.isNotEmpty())append("\n")
                page.data.keyValues.forEachIndexed { i,item ->
                    append("        {")
                    append("\"key\":").append(json(item.key)).append(",")
                    append("\"label\":").append(json(item.label)).append(",")
                    append("\"value\":").append(json(item.value)).append(",")
                    append("\"confidence\":").append(item.confidence).append(",")
                    append("\"reviewed\":").append(item.reviewed).append(",")
                    append("\"source\":").append(json(item.source))
                    append("}")
                    if(i<page.data.keyValues.lastIndex)append(",")
                    append("\n")
                }
                if(page.data.keyValues.isNotEmpty())append("      ")
                append("],\n")
                append("      \"tables\": [")
                if(page.data.tables.isNotEmpty())append("\n")
                page.data.tables.forEachIndexed { ti,table ->
                    append("        {\n")
                    append("          \"id\": ").append(json(table.id)).append(",\n")
                    append("          \"title\": ").append(json(table.title)).append(",\n")
                    append("          \"confidence\": ").append(table.confidence).append(",\n")
                    append("          \"reviewed\": ").append(table.reviewed).append(",\n")
                    append("          \"rows\": [\n")
                    for(row in 0 until table.rowCount) {
                        append("            [")
                        for(col in 0 until table.columnCount) {
                            if(col>0)append(",")
                            append(json(table.cell(row,col)?.text.orEmpty()))
                        }
                        append("]")
                        if(row<table.rowCount-1)append(",")
                        append("\n")
                    }
                    append("          ]\n")
                    append("        }")
                    if(ti<page.data.tables.lastIndex)append(",")
                    append("\n")
                }
                if(page.data.tables.isNotEmpty())append("      ")
                append("]\n")
                append("    }")
                if(pi<pages.lastIndex)append(",")
                append("\n")
            }
            append("  ]\n")
            append("}\n")
        })
        return destination
    }

    fun writeXlsx(pages:List<StructuredExportPage>,destination:File):File {
        destination.parentFile?.mkdirs()
        val sheets=mutableListOf<Pair<String,List<List<String>>>>()
        val keyRows=mutableListOf(
            listOf("Page","Key","Label","Value","Confidence","Reviewed","Source")
        )
        pages.forEach { page ->
            page.data.keyValues.forEach { item ->
                keyRows+=listOf(
                    page.pageNumber.toString(),item.key,item.label,item.value,
                    percent(item.confidence),if(item.reviewed)"Yes" else "No",item.source
                )
            }
        }
        sheets+="Key Values" to keyRows

        pages.forEach { page ->
            page.data.tables.forEachIndexed { index,table ->
                val rows=mutableListOf<List<String>>()
                for(row in 0 until table.rowCount) {
                    rows+=(0 until table.columnCount).map { col ->
                        table.cell(row,col)?.text.orEmpty()
                    }
                }
                val base="P${page.pageNumber} ${table.title.ifBlank{"Table ${index+1}"}}"
                sheets+=base to rows
            }
        }

        val uniqueNames=uniqueSheetNames(sheets.map{it.first})
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            add(zip,"[Content_Types].xml",contentTypes(sheets.size))
            add(zip,"_rels/.rels",rootRels())
            add(zip,"docProps/app.xml",appProps(uniqueNames))
            add(zip,"docProps/core.xml",coreProps())
            add(zip,"xl/workbook.xml",workbook(uniqueNames))
            add(zip,"xl/_rels/workbook.xml.rels",workbookRels(sheets.size))
            add(zip,"xl/styles.xml",styles())
            sheets.forEachIndexed { index,pair ->
                add(zip,"xl/worksheets/sheet${index+1}.xml",worksheet(pair.second))
            }
        }
        return destination
    }

    fun tableAsTsv(table:StructuredTable):String =
        (0 until table.rowCount).joinToString("\n") { row ->
            (0 until table.columnCount).joinToString("\t") { col ->
                table.cell(row,col)?.text.orEmpty()
            }
        }

    private fun csvRow(values:List<String>)=values.joinToString(","){v->
        "\"" + v.replace("\"","\"\"") + "\""
    }
    private fun percent(v:Float)="%.1f%%".format(v*100f)
    private fun json(v:String)="\"" + v
        .replace("\\","\\\\")
        .replace("\"","\\\"")
        .replace("\n","\\n")
        .replace("\r","\\r")
        .replace("\t","\\t") + "\""

    private fun uniqueSheetNames(names:List<String>):List<String> {
        val used=mutableSetOf<String>()
        return names.mapIndexed { index,raw ->
            val clean=raw.replace(Regex("[\\\\/*?:\[\]]")," ")
                .replace(Regex("\\s+")," ").trim().take(31).ifBlank{"Sheet ${index+1}"}
            var candidate=clean
            var suffix=2
            while(candidate.lowercase() in used) {
                val tail=" ($suffix)"
                candidate=clean.take((31-tail.length).coerceAtLeast(1))+tail
                suffix++
            }
            used+=candidate.lowercase()
            candidate
        }
    }

    private fun add(zip:ZipOutputStream,path:String,text:String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun xml(s:String)=s
        .replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        .replace("\"","&quot;").replace("'","&apos;")

    private fun cellRef(column:Int,row:Int):String {
        var n=column+1
        val name=StringBuilder()
        while(n>0) {
            val rem=(n-1)%26
            name.insert(0,('A'.code+rem).toChar())
            n=(n-1)/26
        }
        return name.toString()+row
    }

    private fun worksheet(rows:List<List<String>>):String=buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { ri,row ->
            val r=ri+1
            append("<row r=\"").append(r).append("\">")
            row.forEachIndexed { ci,value ->
                append("<c r=\"").append(cellRef(ci,r)).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                append(xml(value))
                append("</t></is></c>")
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun workbook(names:List<String>)=buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        names.forEachIndexed { i,name ->
            append("<sheet name=\"").append(xml(name)).append("\" sheetId=\"")
                .append(i+1).append("\" r:id=\"rId").append(i+1).append("\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(count:Int)=buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for(i in 1..count) {
            append("<Relationship Id=\"rId").append(i)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                .append(i).append(".xml\"/>")
        }
        append("<Relationship Id=\"rId").append(count+1)
            .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    private fun contentTypes(count:Int)=buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for(i in 1..count)append("<Override PartName=\"/xl/worksheets/sheet").append(i)
            .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
        append("""<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""")
        append("</Types>")
    }

    private fun rootRels()="""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""

    private fun styles()="""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border/></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
</styleSheet>"""

    private fun coreProps()="""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:creator>Scan</dc:creator><cp:lastModifiedBy>Scan</cp:lastModifiedBy>
</cp:coreProperties>"""

    private fun appProps(names:List<String>)=buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"><Application>Scan</Application>""")
        append("<TitlesOfParts><vt:vector size=\"").append(names.size).append("\" baseType=\"lpstr\">")
        names.forEach{append("<vt:lpstr>").append(xml(it)).append("</vt:lpstr>")}
        append("</vt:vector></TitlesOfParts></Properties>")
    }
}
