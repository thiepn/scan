package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class StructuredExportTest {
    private val pages=listOf(
        StructuredExportPage(
            1,
            PageStructuredData(
                sourceToken="x",
                keyValues=listOf(
                    StructuredKeyValue("k","invoice","Invoice","INV-7",.9f,true)
                ),
                tables=listOf(
                    StructuredTable(
                        "t","Items",2,2,
                        listOf(
                            StructuredCell(0,0,"Item",.9f,true),
                            StructuredCell(0,1,"Price",.9f,true),
                            StructuredCell(1,0,"Tea",.8f,false),
                            StructuredCell(1,1,"2.50",.8f,false)
                        ),
                        .85f,true
                    )
                )
            )
        )
    )

    @Test fun csvAndJsonEscapeStructuredValues() {
        val dir=kotlin.io.path.createTempDirectory("structured-export").toFile()
        try {
            val csv=File(dir,"data.csv")
            val json=File(dir,"data.json")
            StructuredDataExport.writeCsv(pages,csv)
            StructuredDataExport.writeJson("d","Title",pages,json)
            assertTrue(csv.readText().contains("\"Tea\""))
            assertTrue(json.readText().contains("\"invoice\""))
            assertTrue(json.readText().contains("\"INV-7\""))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun xlsxContainsWorkbookAndWorksheets() {
        val dir=kotlin.io.path.createTempDirectory("structured-xlsx").toFile()
        try {
            val file=File(dir,"data.xlsx")
            StructuredDataExport.writeXlsx(pages,file)
            assertTrue(file.length()>0)
            ZipFile(file).use { zip ->
                assertTrue(zip.getEntry("[Content_Types].xml")!=null)
                assertTrue(zip.getEntry("xl/workbook.xml")!=null)
                assertTrue(zip.getEntry("xl/worksheets/sheet1.xml")!=null)
                assertTrue(zip.getEntry("xl/worksheets/sheet2.xml")!=null)
                val sheet2=zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml"))
                    .bufferedReader().readText()
                assertTrue(sheet2.contains("Tea"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun tableTsvIsCopyable() {
        val t=pages.single().data.tables.single()
        assertEquals("Item\tPrice\nTea\t2.50",StructuredDataExport.tableAsTsv(t))
    }
}
