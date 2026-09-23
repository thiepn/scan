package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMarkupTest {
    @Test fun codecAndLayersRoundTrip() {
        var r=PageMarkupRecipe()
        r=PageMarkupEngine.withElement(r,PageMarkupElement("a",PageMarkupKind.RECTANGLE,left=.1f,top=.1f,right=.2f,bottom=.2f))
        r=PageMarkupEngine.withElement(r,PageMarkupElement("b",PageMarkupKind.SIGNATURE,points=listOf(MarkupPoint(.2f,.8f),MarkupPoint(.7f,.82f))))
        val d=PageMarkupRecipeCodec.decode(PageMarkupRecipeCodec.encode(r))
        assertEquals(listOf("a","b"),d.elements.map{it.id})
        assertEquals(2,d.elements.last().points.size)
        assertEquals(listOf("b","a"),PageMarkupEngine.move(d,"a",1).elements.map{it.id})
    }

    @Test fun redactionRemovesSearchableWord() {
        val words=listOf(word("Invoice",0,100,260),word("secret",1,300,520),word("total",2,560,720))
        val base=OcrPageResult(
            "Invoice secret total",
            listOf(OcrTextBlock(0,0,"Invoice secret total",100,80,720,180,emptyList(),"en")),
            listOf(OcrTextLine(0,0,0,"Invoice secret total",100,80,720,180,emptyList(),"en",1f,0f)),
            words,1000,1000,OcrScript.LATIN
        )
        val recipe=PageMarkupRecipe(elements=listOf(PageMarkupElement(
            "r",PageMarkupKind.REDACTION,left=.28f,top=.05f,right=.55f,bottom=.18f,filled=true
        )))
        val out=PageMarkupOcr.applyRedactions(base,recipe)
        assertTrue(out.text.contains("Invoice")); assertFalse(out.text.contains("secret"))
        assertEquals(listOf("Invoice","total"),out.words.map{it.text})
    }

    private fun word(s:String,i:Int,l:Int,r:Int)=OcrWordBox(0,0,i,i,s,l,90,r,170,emptyList(),"en",1f,0f)
}
