package com.thiepn.scan.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Base64
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class FormFieldType(val label: String) {
    TEXT("Text"),
    DATE("Date"),
    CHECKBOX("Checkbox"),
    SIGNATURE("Signature")
}

data class FormPoint(val x: Float, val y: Float) {
    fun clamped() = FormPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

data class FormField(
    val id: String,
    val type: FormFieldType,
    val label: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val value: String = "",
    val checked: Boolean = false,
    val required: Boolean = false,
    val signature: List<FormPoint> = emptyList(),
    val source: String = "MANUAL",
    val confidence: Float = 1f,
    val fieldOrder: Int = 0
) {
    fun normalized(): FormField {
        val l=min(left,right).coerceIn(0f,1f)
        val r=max(left,right).coerceIn(0f,1f)
        val t=min(top,bottom).coerceIn(0f,1f)
        val b=max(top,bottom).coerceIn(0f,1f)
        return copy(
            label=label.trim().take(180),
            left=l,
            top=t,
            right=max(r,l+0.008f).coerceAtMost(1f),
            bottom=max(b,t+0.008f).coerceAtMost(1f),
            value=value.take(2000),
            signature=signature.map(FormPoint::clamped).take(800),
            confidence=confidence.coerceIn(0f,1f),
            fieldOrder=fieldOrder.coerceAtLeast(0)
        )
    }

    fun isFilled(): Boolean = when(type) {
        FormFieldType.TEXT, FormFieldType.DATE -> value.isNotBlank()
        FormFieldType.CHECKBOX -> checked
        FormFieldType.SIGNATURE -> signature.size >= 2
    }

    fun searchableText(): String = when(type) {
        FormFieldType.TEXT, FormFieldType.DATE -> value.trim()
        FormFieldType.CHECKBOX, FormFieldType.SIGNATURE -> ""
    }

    fun blankValue(): FormField = copy(value="",checked=false,signature=emptyList())
}

data class PageFormRecipe(
    val version: Int = 1,
    val fields: List<FormField> = emptyList()
) {
    fun normalized(): PageFormRecipe {
        val map=LinkedHashMap<String,FormField>()
        fields.map(FormField::normalized).forEach { map[it.id]=it }
        return PageFormRecipe(
            1,
            map.values.sortedWith(
                compareBy<FormField>{it.fieldOrder}.thenBy{it.top}.thenBy{it.left}
            ).take(180).mapIndexed { i,f -> f.copy(fieldOrder=i) }
        )
    }
    fun isEmpty()=fields.isEmpty()
    fun hasFillContent()=fields.any(FormField::isFilled)
    fun hasSearchableValues()=fields.any{it.searchableText().isNotBlank()}
    fun blankValues()=PageFormRecipe(fields=fields.map(FormField::blankValue)).normalized()
}

object PageFormRecipeCodec {
    private const val F="\t"
    private const val R="\n"
    fun encode(recipe: PageFormRecipe?): String? {
        val n=recipe?.normalized()?:return null
        if(n.isEmpty())return null
        return buildList{
            add("H"+F+"1")
            n.fields.forEach { x ->
                add(listOf(
                    "F",x.id,x.type.name,x.left,x.top,x.right,x.bottom,x.fieldOrder,
                    if(x.required)1 else 0,if(x.checked)1 else 0,x.confidence,
                    enc(x.label),enc(x.value),enc(x.source),encPts(x.signature)
                ).joinToString(F))
            }
        }.joinToString(R)
    }
    fun decode(encoded:String?):PageFormRecipe {
        if(encoded.isNullOrBlank())return PageFormRecipe()
        val rows=encoded.split(R)
        if(rows.firstOrNull()!="H"+F+"1")return PageFormRecipe()
        val fields=rows.drop(1).mapNotNull { row ->
            val p=row.split(F)
            if(p.size!=15||p[0]!="F")return@mapNotNull null
            runCatching {
                FormField(
                    id=p[1],type=FormFieldType.valueOf(p[2]),
                    left=p[3].toFloat(),top=p[4].toFloat(),right=p[5].toFloat(),bottom=p[6].toFloat(),
                    fieldOrder=p[7].toInt(),required=p[8]=="1",checked=p[9]=="1",
                    confidence=p[10].toFloat(),label=dec(p[11]),value=dec(p[12]),
                    source=dec(p[13]),signature=decPts(p[14])
                ).normalized()
            }.getOrNull()
        }
        return PageFormRecipe(fields=fields).normalized()
    }
    private fun enc(s:String)=if(s.isEmpty())"~" else Base64.getUrlEncoder().withoutPadding()
        .encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun dec(s:String)=if(s=="~")"" else runCatching {
        Base64.getUrlDecoder().decode(s).toString(Charsets.UTF_8)
    }.getOrDefault("")
    private fun encPts(ps:List<FormPoint>)=if(ps.isEmpty())"~" else ps.joinToString(";"){"${it.x},${it.y}"}
    private fun decPts(s:String):List<FormPoint>{
        if(s=="~")return emptyList()
        return s.split(";").mapNotNull {
            val q=it.split(","); if(q.size!=2)null else {
                val x=q[0].toFloatOrNull();val y=q[1].toFloatOrNull()
                if(x==null||y==null)null else FormPoint(x,y)
            }
        }
    }
}

data class FormTemplateBundle(
    val version: Int = 1,
    val pages: List<PageFormRecipe>
)

object FormTemplateCodec {
    fun encode(bundle:FormTemplateBundle):String =
        buildList {
            add("H\t1\t${bundle.pages.size}")
            bundle.pages.forEachIndexed { i,r ->
                val encoded=PageFormRecipeCodec.encode(r.blankValues()).orEmpty()
                val payload=Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(encoded.toByteArray(Charsets.UTF_8))
                add("P\t$i\t$payload")
            }
        }.joinToString("\n")

    fun decode(encoded:String?):FormTemplateBundle? {
        if(encoded.isNullOrBlank())return null
        val rows=encoded.split("\n")
        val h=rows.firstOrNull()?.split("\t").orEmpty()
        if(h.size!=3||h[0]!="H"||h[1]!="1")return null
        val count=h[2].toIntOrNull()?:return null
        val pages=MutableList(count){PageFormRecipe()}
        rows.drop(1).forEach { row ->
            val p=row.split("\t");if(p.size!=3||p[0]!="P")return@forEach
            val i=p[1].toIntOrNull()?:return@forEach
            if(i !in pages.indices)return@forEach
            val raw=runCatching { Base64.getUrlDecoder().decode(p[2]).toString(Charsets.UTF_8) }.getOrNull()
                ?:return@forEach
            pages[i]=PageFormRecipeCodec.decode(raw).blankValues()
        }
        return FormTemplateBundle(pages=pages)
    }
}

object FormFieldDetector {
    private val dateTerms=listOf("date","datum","dob","birth","geburt","birthday")
    private val signatureTerms=listOf("signature","sign here","unterschrift","signed by")
    private val genericTerms=listOf(
        "name","first name","last name","surname","vorname","nachname",
        "address","adresse","street","straße","strasse","city","stadt",
        "zip","postal","plz","country","land","email","e-mail","phone","telefon",
        "company","firma","organization","organisation","department","abteilung",
        "title","position","reference","number","no.","id"
    )
    private val checkboxRegex=Regex("""[☐□☑✓]|\[\s?\]|\(\s?\)""")
    private val underscoreRegex=Regex("""_{2,}""")

    fun detect(result:OcrPageResult?):PageFormRecipe {
        result?:return PageFormRecipe()
        if(result.sourceWidth<=0||result.sourceHeight<=0)return PageFormRecipe()
        val w=result.sourceWidth.toFloat();val h=result.sourceHeight.toFloat()
        val out=mutableListOf<FormField>()

        result.lines.sortedBy{it.readingOrder}.forEach { line ->
            val raw=line.text.trim();if(raw.length !in 1..100)return@forEach
            val lower=raw.lowercase()
            val hasCheckbox=checkboxRegex.containsMatchIn(raw)
            val hasSignature=signatureTerms.any{it in lower}
            val hasDate=dateTerms.any{it in lower}
            val isGeneric=raw.endsWith(":")||underscoreRegex.containsMatchIn(raw)||
                genericTerms.any { term -> lower==term||lower.startsWith(term+":")||lower.startsWith(term+" ") }
            val type=when {
                hasCheckbox->FormFieldType.CHECKBOX
                hasSignature->FormFieldType.SIGNATURE
                hasDate->FormFieldType.DATE
                isGeneric->FormFieldType.TEXT
                else->return@forEach
            }

            val l=line.left/w;val t=line.top/h;val r=line.right/w;val b=line.bottom/h
            val lineH=(b-t).coerceAtLeast(0.018f)
            val label=raw
                .replace(checkboxRegex,"")
                .replace(underscoreRegex,"")
                .trim(' ',':','-','–')
                .ifBlank{type.label}
                .take(180)
            val required="*" in raw||"required" in lower||"pflicht" in lower

            val rect=when(type) {
                FormFieldType.CHECKBOX -> {
                    val size=(lineH*1.15f).coerceIn(0.022f,0.065f)
                    floatArrayOf(l,t,min(1f,l+size),min(1f,t+size))
                }
                FormFieldType.SIGNATURE -> {
                    val start=if(0.94f-r>=0.28f)min(0.92f,r+0.012f) else l
                    val top=if(start>l)t else min(0.92f,b+0.008f)
                    val bottom=min(0.98f,top+max(0.07f,lineH*2.6f))
                    floatArrayOf(start,top,0.94f,bottom)
                }
                else -> {
                    val rightSpace=0.94f-r
                    if(rightSpace>=0.22f) {
                        floatArrayOf(min(0.93f,r+0.012f),max(0f,t-lineH*.12f),0.94f,min(1f,b+lineH*.12f))
                    } else {
                        val top=min(0.95f,b+0.006f)
                        floatArrayOf(l,top,0.94f,min(0.99f,top+max(0.038f,lineH*1.45f)))
                    }
                }
            }
            if(rect[2]-rect[0]<0.012f||rect[3]-rect[1]<0.012f)return@forEach

            out+=FormField(
                id="D:${line.blockIndex}:${line.lineIndex}:${type.name}",
                type=type,label=label,left=rect[0],top=rect[1],right=rect[2],bottom=rect[3],
                required=required,source="OCR",confidence=line.confidence,fieldOrder=out.size
            )
        }
        return PageFormRecipe(fields=out).normalized()
    }

    fun merge(current:PageFormRecipe,detected:PageFormRecipe):PageFormRecipe {
        val existing=current.normalized().fields.toMutableList()
        detected.normalized().fields.forEach { candidate ->
            val same=existing.indexOfFirst{it.id==candidate.id}
            if(same>=0) {
                val old=existing[same]
                existing[same]=candidate.copy(
                    value=old.value,checked=old.checked,signature=old.signature,
                    required=old.required||candidate.required,fieldOrder=old.fieldOrder
                )
            } else if(existing.none{overlap(it,candidate)>=0.70f}) {
                existing+=candidate.copy(fieldOrder=existing.size)
            }
        }
        return PageFormRecipe(fields=existing).normalized()
    }

    private fun overlap(a:FormField,b:FormField):Float {
        val l=max(a.left,b.left);val t=max(a.top,b.top);val r=min(a.right,b.right);val bot=min(a.bottom,b.bottom)
        if(r<=l||bot<=t)return 0f
        val inter=(r-l)*(bot-t)
        val smaller=min((a.right-a.left)*(a.bottom-a.top),(b.right-b.left)*(b.bottom-b.top)).coerceAtLeast(.00001f)
        return inter/smaller
    }
}

data class FormValidationIssue(val fieldId:String,val message:String)

object FormValidator {
    private val dateRegex=Regex("""^\s*(?:\d{1,2}[./-]\d{1,2}[./-]\d{2,4}|\d{4}[./-]\d{1,2}[./-]\d{1,2})\s*$""")
    fun validate(recipe:PageFormRecipe):List<FormValidationIssue> =
        recipe.normalized().fields.mapNotNull { f ->
            when {
                f.required&&!f.isFilled()->FormValidationIssue(f.id,"${f.label.ifBlank{f.type.label}} is required.")
                f.type==FormFieldType.DATE&&f.value.isNotBlank()&&!dateRegex.matches(f.value)->
                    FormValidationIssue(f.id,"${f.label.ifBlank{"Date"}} should use a recognizable date format.")
                else->null
            }
        }
}

object FormFillOcr {
    fun apply(base:OcrPageResult,recipe:PageFormRecipe):OcrPageResult {
        val fields=recipe.normalized().fields.filter{it.searchableText().isNotBlank()}
        if(fields.isEmpty()||base.sourceWidth<=0||base.sourceHeight<=0)return base
        val blocks=base.blocks.toMutableList();val lines=base.lines.toMutableList();val words=base.words.toMutableList()
        var nextBlock=(base.blocks.maxOfOrNull{it.blockIndex}?:0)+1000
        var order=(base.lines.maxOfOrNull{it.readingOrder}?:-1)+1
        fields.forEach { f ->
            val values=f.value.split('\n').map{it.trim()}.filter{it.isNotBlank()}
            if(values.isEmpty())return@forEach
            val l=(f.left*base.sourceWidth).roundToInt();val r=(f.right*base.sourceWidth).roundToInt().coerceAtLeast(l+1)
            val top=(f.top*base.sourceHeight).roundToInt();val bot=(f.bottom*base.sourceHeight).roundToInt().coerceAtLeast(top+1)
            val slot=(bot-top).toFloat()/values.size
            val blockIndex=nextBlock++
            values.forEachIndexed { idx,value ->
                val lt=(top+slot*idx).roundToInt()
                val lb=(top+slot*(idx+1)).roundToInt().coerceAtLeast(lt+1)
                val lineIndex=idx
                val ro=order++
                lines+=OcrTextLine(blockIndex,lineIndex,ro,value,l,lt,r,lb,emptyList(),"",1f,0f)
                val tokens=Regex("""\S+""").findAll(value).map{it.value}.toList()
                val units=(tokens.sumOf{it.length}+max(0,tokens.size-1)).coerceAtLeast(1)
                var used=0
                tokens.forEachIndexed { wi,token ->
                    val wl=l+((r-l)*used.toFloat()/units).roundToInt()
                    used+=token.length
                    val wr=if(wi==tokens.lastIndex)r else l+((r-l)*used.toFloat()/units).roundToInt()
                    used++
                    words+=OcrWordBox(blockIndex,lineIndex,wi,words.size,token,wl,lt,wr.coerceAtLeast(wl+1),lb,emptyList(),"",1f,0f)
                }
            }
            blocks+=OcrTextBlock(
                blockIndex=blockIndex,readingOrder=blocks.size,
                text=values.joinToString("\n"),left=l,top=top,right=r,bottom=bot,
                cornerPoints=emptyList(),languageTag=""
            )
        }
        val extra=fields.joinToString("\n"){it.searchableText()}.trim()
        val text=listOf(base.text.trim(),extra).filter{it.isNotBlank()}.joinToString("\n\n")
        return base.copy(text=text,blocks=blocks,lines=lines,words=words)
    }
}

object FormFillRenderer {
    fun apply(source:Bitmap,recipe:PageFormRecipe):Bitmap {
        val fields=recipe.normalized().fields.filter(FormField::isFilled)
        if(fields.isEmpty()||source.width<2||source.height<2)return source
        val out=source.copy(Bitmap.Config.ARGB_8888,true)?:return source
        val c=Canvas(out);val w=out.width.toFloat();val h=out.height.toFloat();val minEdge=min(w,h)
        fields.forEach { f ->
            val r=RectF(f.left*w,f.top*h,f.right*w,f.bottom*h)
            val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color=Color.rgb(25,25,25);strokeWidth=max(1.5f,minEdge*.0035f);strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
            }
            when(f.type) {
                FormFieldType.TEXT,FormFieldType.DATE->drawText(c,r,f.value,p)
                FormFieldType.CHECKBOX->if(f.checked)drawCheck(c,r,p)
                FormFieldType.SIGNATURE->drawSignature(c,r,f.signature,p)
            }
        }
        return out
    }
    private fun drawText(c:Canvas,r:RectF,value:String,p:Paint) {
        val lines=value.split('\n').take(10);if(lines.isEmpty())return
        val slot=r.height()/lines.size.coerceAtLeast(1)
        lines.forEachIndexed { i,s ->
            if(s.isBlank())return@forEachIndexed
            p.style=Paint.Style.FILL;p.textAlign=Paint.Align.LEFT
            p.textSize=fit(p,s,(r.width()*.98f).coerceAtLeast(1f),(slot*.72f).coerceAtLeast(7f))
            val fm=p.fontMetrics
            val base=r.top+i*slot+(slot-(fm.bottom-fm.top))/2f-fm.top
            c.drawText(s,r.left+r.width()*.01f,base,p)
        }
    }
    private fun drawCheck(c:Canvas,r:RectF,p:Paint) {
        p.style=Paint.Style.STROKE;p.strokeWidth=max(2f,min(r.width(),r.height())*.12f)
        val path=Path()
        path.moveTo(r.left+r.width()*.18f,r.top+r.height()*.54f)
        path.lineTo(r.left+r.width()*.42f,r.top+r.height()*.78f)
        path.lineTo(r.left+r.width()*.84f,r.top+r.height()*.20f)
        c.drawPath(path,p)
    }
    private fun drawSignature(c:Canvas,r:RectF,pts:List<FormPoint>,p:Paint) {
        if(pts.size<2)return
        p.style=Paint.Style.STROKE;p.strokeWidth=max(1.5f,min(r.width(),r.height())*.025f)
        val path=Path()
        pts.forEachIndexed { i,q ->
            val x=r.left+q.x*r.width();val y=r.top+q.y*r.height()
            if(i==0)path.moveTo(x,y) else path.lineTo(x,y)
        }
        c.drawPath(path,p)
    }
    private fun fit(p:Paint,s:String,maxW:Float,maxS:Float):Float {
        var lo=4f;var hi=max(lo,maxS)
        repeat(10){val m=(lo+hi)/2;p.textSize=m;if(p.measureText(s)<=maxW)lo=m else hi=m}
        return lo
    }
}
