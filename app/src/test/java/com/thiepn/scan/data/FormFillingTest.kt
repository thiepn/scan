package com.thiepn.scan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormFillingTest {
    @Test
    fun recipeCodecPreservesValuesCheckboxAndMultistrokeSignature() {
        val recipe = PageFormRecipe(
            fields = listOf(
                FormField("name", FormFieldType.TEXT, "Name", .1f,.1f,.8f,.16f,value="Ada Lovelace"),
                FormField("ok", FormFieldType.CHECKBOX, "Agree", .1f,.2f,.15f,.25f,checked=true),
                FormField(
                    "sig",FormFieldType.SIGNATURE,"Signature",.1f,.7f,.8f,.84f,
                    signature=listOf(
                        FormPoint(.1f,.5f,true),FormPoint(.4f,.3f),
                        FormPoint(.5f,.7f,true),FormPoint(.8f,.4f)
                    )
                )
            )
        )
        val out=PageFormRecipeCodec.decode(PageFormRecipeCodec.encode(recipe))
        assertEquals("Ada Lovelace",out.fields[0].value)
        assertTrue(out.fields[1].checked)
        assertEquals(2,out.fields[2].signature.count{it.strokeStart})
    }

    @Test
    fun detectorFindsTextDateCheckboxAndSignatureLabels() {
        val result=OcrPageResult(
            text="Name:\nDate:\n☐ I agree\nSignature:",
            blocks=emptyList(),
            lines=listOf(
                line(0,"Name:",100),line(1,"Date:",180),
                line(2,"☐ I agree",260),line(3,"Signature:",340)
            ),
            words=emptyList(),
            sourceWidth=1000,
            sourceHeight=1200,
            script=OcrScript.LATIN
        )
        val types=FormFieldDetector.detect(result).fields.map{it.type}.toSet()
        assertTrue(FormFieldType.TEXT in types)
        assertTrue(FormFieldType.DATE in types)
        assertTrue(FormFieldType.CHECKBOX in types)
        assertTrue(FormFieldType.SIGNATURE in types)
    }

    @Test
    fun validationAllowsPartialSaveButReportsRequiredAndBadDates() {
        val recipe=PageFormRecipe(fields=listOf(
            FormField("name",FormFieldType.TEXT,"Name",.1f,.1f,.8f,.2f,required=true),
            FormField("date",FormFieldType.DATE,"Date",.1f,.3f,.8f,.4f,value="not-a-date")
        ))
        val issues=FormValidator.validate(recipe)
        assertEquals(2,issues.size)
    }

    @Test
    fun formValuesBecomeSearchableButCheckboxAndSignatureDoNotAddText() {
        val base=OcrPageResult("Form",emptyList(),emptyList(),emptyList(),1000,1000,OcrScript.LATIN)
        val recipe=PageFormRecipe(fields=listOf(
            FormField("name",FormFieldType.TEXT,"Name",.1f,.1f,.8f,.2f,value="Grace Hopper"),
            FormField("check",FormFieldType.CHECKBOX,"Agree",.1f,.3f,.2f,.4f,checked=true)
        ))
        val out=FormFillOcr.apply(base,recipe)
        assertTrue(out.text.contains("Grace Hopper"))
        assertFalse(out.text.contains("Agree"))
    }

    @Test
    fun templateCodecStripsEnteredValues() {
        val bundle=FormTemplateBundle(pages=listOf(PageFormRecipe(fields=listOf(
            FormField("name",FormFieldType.TEXT,"Name",.1f,.1f,.8f,.2f,value="Private value")
        ))))
        val out=requireNotNull(FormTemplateCodec.decode(FormTemplateCodec.encode(bundle)))
        assertEquals("",out.pages.single().fields.single().value)
    }

    private fun line(index:Int,text:String,top:Int)=OcrTextLine(
        blockIndex=0,lineIndex=index,readingOrder=index,text=text,
        left=100,top=top,right=420,bottom=top+55,
        cornerPoints=emptyList(),languageTag="en",confidence=.95f,angleDegrees=0f
    )
}
