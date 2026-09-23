package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredDataTest {
    @Test
    fun codecRoundTripsTablesAndKeyValues() {
        val data=PageStructuredData(
            sourceToken="abc",
            tables=listOf(
                StructuredTable(
                    id="t",title="Items",rowCount=2,columnCount=2,
                    cells=listOf(
                        StructuredCell(0,0,"Item",.9f,true),
                        StructuredCell(0,1,"Price",.9f,true),
                        StructuredCell(1,0,"Tea",.8f,false),
                        StructuredCell(1,1,"2.50",.8f,false)
                    ),
                    confidence=.85f
                )
            ),
            keyValues=listOf(
                StructuredKeyValue("k","invoice_number","Invoice number","A-42",.9f)
            )
        )
        val out=PageStructuredDataCodec.decode(PageStructuredDataCodec.encode(data))
        assertEquals("abc",out.sourceToken)
        assertEquals("Tea",out.tables.single().cell(1,0)?.text)
        assertEquals("A-42",out.keyValues.single().value)
    }

    @Test
    fun alignedRowsBecomeTable() {
        val page=page(
            listOf(
                word(0,0,0,"Item",80,180,100),
                word(0,0,1,"Qty",410,470,100),
                word(0,0,2,"Price",700,820,100),
                word(0,1,0,"Tea",80,160,180),
                word(0,1,1,"2",420,450,180),
                word(0,1,2,"2.50",710,790,180),
                word(0,2,0,"Cake",80,180,260),
                word(0,2,1,"1",420,450,260),
                word(0,2,2,"4.00",710,790,260)
            )
        )
        val tables=StructuredDataDetector.detect(page).tables
        assertTrue(tables.isNotEmpty())
        assertEquals(3,tables.first().columnCount)
        assertEquals("Tea",tables.first().cell(1,0)?.text)
    }

    @Test
    fun receiptAmountsProduceLineItemsFallback() {
        val result=OcrPageResult(
            text="Coffee 3.20\nCake 4.50\nTOTAL 7.70",
            blocks=emptyList(),
            lines=listOf(
                line(0,"Coffee 3.20",100),
                line(1,"Cake 4.50",180),
                line(2,"TOTAL 7.70",260)
            ),
            words=emptyList(),
            sourceWidth=1000,sourceHeight=1400,script=OcrScript.LATIN
        )
        val table=StructuredDataDetector.detect(result,ScanMode.RECEIPT).tables
            .firstOrNull{it.id=="LINE_ITEMS"}
        assertTrue(table!=null)
        assertEquals("Coffee",table?.cell(1,0)?.text)
    }

    @Test
    fun schemaAddsMissingCanonicalFieldsWithoutValues() {
        val data=PageStructuredData(
            sourceToken="x",
            keyValues=listOf(
                StructuredKeyValue("k","inv_no","Invoice No","42",.9f)
            )
        )
        val schema=ExtractionSchemaDefinition(fields=listOf(
            ExtractionSchemaField("invoice_number","Invoice number",listOf("Invoice No","inv no")),
            ExtractionSchemaField("customer","Customer",listOf("Client"))
        ))
        val out=StructuredSchemaMatcher.apply(data,schema)
        assertEquals("42",out.keyValues.first{it.key=="invoice_number"}.value)
        assertEquals("",out.keyValues.first{it.key=="customer"}.value)
    }

    @Test
    fun lowConfidenceItemsRequireReview() {
        val data=PageStructuredData(
            keyValues=listOf(StructuredKeyValue("k","x","X","v",.4f))
        )
        assertTrue(data.reviewCount()>0)
        val reviewed=data.copy(keyValues=data.keyValues.map{it.copy(reviewed=true)})
        assertEquals(0,reviewed.reviewCount())
    }

    private fun page(words:List<OcrWordBox>)=OcrPageResult(
        text=words.joinToString(" "){it.text},
        blocks=emptyList(),lines=emptyList(),words=words,
        sourceWidth=1000,sourceHeight=1400,script=OcrScript.LATIN
    )

    private fun word(block:Int,line:Int,index:Int,text:String,left:Int,right:Int,top:Int)=
        OcrWordBox(
            block,line,index,index,text,left,top,right,top+45,
            emptyList(),"en",.92f,0f
        )

    private fun line(index:Int,text:String,top:Int)=OcrTextLine(
        0,index,index,text,80,top,850,top+50,emptyList(),"en",.9f,0f
    )
}
