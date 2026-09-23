package com.thiepn.scan.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Base64
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class PageMarkupKind(val label: String) {
    REDACTION("Redaction"), HIGHLIGHT("Highlight"), FREEHAND("Pen"),
    RECTANGLE("Rectangle"), ELLIPSE("Ellipse"), ARROW("Arrow"),
    TEXT("Text"), SIGNATURE("Signature"), STAMP("Stamp")
}

data class MarkupPoint(val x: Float, val y: Float) {
    fun clamped() = MarkupPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

data class PageMarkupElement(
    val id: String,
    val kind: PageMarkupKind,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val points: List<MarkupPoint> = emptyList(),
    val text: String = "",
    val colorArgb: Int = 0xFF111111.toInt(),
    val strokeWidth: Float = 0.006f,
    val opacity: Float = 1f,
    val filled: Boolean = false,
    val zIndex: Int = 0
) {
    fun normalized(): PageMarkupElement {
        val l = min(left, right).coerceIn(0f, 1f)
        val r = max(left, right).coerceIn(0f, 1f)
        val t = min(top, bottom).coerceIn(0f, 1f)
        val b = max(top, bottom).coerceIn(0f, 1f)
        return copy(
            left=l, top=t, right=r, bottom=b,
            points=points.map(MarkupPoint::clamped).take(600),
            text=text.take(1200),
            strokeWidth=strokeWidth.coerceIn(0.0015f,0.06f),
            opacity=opacity.coerceIn(0.05f,1f),
            zIndex=zIndex.coerceAtLeast(0)
        )
    }
}

data class PageMarkupRecipe(
    val version: Int = 1,
    val elements: List<PageMarkupElement> = emptyList()
) {
    fun normalized(): PageMarkupRecipe {
        val map=LinkedHashMap<String,PageMarkupElement>()
        elements.map(PageMarkupElement::normalized).forEach { map[it.id]=it }
        return PageMarkupRecipe(
            1,
            map.values.sortedWith(compareBy<PageMarkupElement>{it.zIndex}.thenBy{it.id})
                .takeLast(240)
                .mapIndexed { i,e -> e.copy(zIndex=i) }
        )
    }
    fun isEmpty()=elements.isEmpty()
    fun hasRedactions()=elements.any{it.kind==PageMarkupKind.REDACTION}
}

object PageMarkupRecipeCodec {
    private const val F="\t"
    private const val R="\n"
    fun encode(recipe: PageMarkupRecipe?): String? {
        val n=recipe?.normalized() ?: return null
        if(n.isEmpty()) return null
        return buildList {
            add("H"+F+"1")
            n.elements.forEach { e ->
                add(listOf(
                    "E",e.id,e.kind.name,e.left,e.top,e.right,e.bottom,e.colorArgb,
                    e.strokeWidth,e.opacity,if(e.filled)1 else 0,e.zIndex,
                    enc(e.text),encPts(e.points)
                ).joinToString(F))
            }
        }.joinToString(R)
    }
    fun decode(encoded:String?):PageMarkupRecipe {
        if(encoded.isNullOrBlank()) return PageMarkupRecipe()
        val rows=encoded.split(R)
        if(rows.firstOrNull()!="H"+F+"1") return PageMarkupRecipe()
        val out=rows.drop(1).mapNotNull { row ->
            val f=row.split(F)
            if(f.size!=14 || f[0]!="E") return@mapNotNull null
            runCatching {
                PageMarkupElement(
                    id=f[1],kind=PageMarkupKind.valueOf(f[2]),
                    left=f[3].toFloat(),top=f[4].toFloat(),right=f[5].toFloat(),bottom=f[6].toFloat(),
                    colorArgb=f[7].toInt(),strokeWidth=f[8].toFloat(),opacity=f[9].toFloat(),
                    filled=f[10]=="1",zIndex=f[11].toInt(),text=dec(f[12]),points=decPts(f[13])
                ).normalized()
            }.getOrNull()
        }
        return PageMarkupRecipe(elements=out).normalized()
    }
    private fun enc(s:String)=if(s.isEmpty())"~" else Base64.getUrlEncoder().withoutPadding()
        .encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun dec(s:String)=if(s=="~")"" else Base64.getUrlDecoder().decode(s).toString(Charsets.UTF_8)
    private fun encPts(p:List<MarkupPoint>)=if(p.isEmpty())"~" else p.joinToString(";"){it.x.toString()+","+it.y.toString()}
    private fun decPts(s:String):List<MarkupPoint> {
        if(s=="~") return emptyList()
        return s.split(";").mapNotNull {
            val q=it.split(","); if(q.size!=2) null else {
                val x=q[0].toFloatOrNull(); val y=q[1].toFloatOrNull()
                if(x==null||y==null)null else MarkupPoint(x,y)
            }
        }
    }
}

object PageMarkupEngine {
    fun withElement(r:PageMarkupRecipe,e:PageMarkupElement):PageMarkupRecipe {
        val cur=r.normalized().elements.filterNot{it.id==e.id}
        return PageMarkupRecipe(elements=cur+e.normalized().copy(zIndex=cur.size)).normalized()
    }
    fun remove(r:PageMarkupRecipe,id:String)=PageMarkupRecipe(elements=r.normalized().elements.filterNot{it.id==id}).normalized()
    fun move(r:PageMarkupRecipe,id:String,delta:Int):PageMarkupRecipe {
        val list=r.normalized().elements.toMutableList()
        val i=list.indexOfFirst{it.id==id}; if(i<0)return r.normalized()
        val t=(i+delta).coerceIn(0,list.lastIndex); if(t==i)return r.normalized()
        val e=list.removeAt(i); list.add(t,e)
        return PageMarkupRecipe(elements=list.mapIndexed{z,x->x.copy(zIndex=z)})
    }
}

object PageMarkupOcr {
    fun applyRedactions(base:OcrPageResult,recipe:PageMarkupRecipe):OcrPageResult {
        val reds=recipe.normalized().elements.filter{it.kind==PageMarkupKind.REDACTION}
        if(reds.isEmpty()||base.sourceWidth<=0||base.sourceHeight<=0)return base
        fun hit(l:Int,t:Int,r:Int,b:Int):Boolean {
            val lf=l.toFloat()/base.sourceWidth; val tf=t.toFloat()/base.sourceHeight
            val rf=r.toFloat()/base.sourceWidth; val bf=b.toFloat()/base.sourceHeight
            return reds.any{rf>it.left&&lf<it.right&&bf>it.top&&tf<it.bottom}
        }
        val words=base.words.filterNot{hit(it.left,it.top,it.right,it.bottom)}
        val byLine=words.groupBy{it.blockIndex to it.lineIndex}
        val lines=base.lines.mapNotNull { line ->
            val ws=byLine[line.blockIndex to line.lineIndex].orEmpty().sortedBy{it.left}
            when {
                ws.isNotEmpty()->line.copy(text=ws.joinToString(" "){it.text})
                hit(line.left,line.top,line.right,line.bottom)->null
                else->line
            }
        }.filter{it.text.isNotBlank()}.sortedBy{it.readingOrder}
            .mapIndexed{i,l->l.copy(readingOrder=i)}
        val blocks=base.blocks.mapNotNull { block ->
            val ls=lines.filter{it.blockIndex==block.blockIndex}.sortedBy{it.readingOrder}
            when {
                ls.isNotEmpty()->block.copy(text=ls.joinToString("\n"){it.text})
                hit(block.left,block.top,block.right,block.bottom)->null
                else->block
            }
        }.filter{it.text.isNotBlank()}
        val text=if(blocks.isNotEmpty()) blocks.sortedBy{it.readingOrder}.joinToString("\n\n"){it.text.trim()}
            else lines.joinToString("\n"){it.text.trim()}
        return base.copy(text=text,blocks=blocks,lines=lines,words=words.mapIndexed{i,w->w.copy(readingOrder=i)})
    }
}

object PageMarkupRenderer {
    fun apply(source:Bitmap,recipe:PageMarkupRecipe):Bitmap {
        val es=recipe.normalized().elements
        if(es.isEmpty()||source.width<2||source.height<2)return source
        val out=source.copy(Bitmap.Config.ARGB_8888,true)?:return source
        val c=Canvas(out); es.sortedBy{it.zIndex}.forEach{draw(c,out,it)}; return out
    }
    private fun draw(c:Canvas,b:Bitmap,e:PageMarkupElement) {
        val w=b.width.toFloat(); val h=b.height.toFloat(); val m=min(w,h)
        val r=RectF(e.left*w,e.top*h,e.right*w,e.bottom*h)
        val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color=if(e.kind==PageMarkupKind.REDACTION)Color.BLACK else e.colorArgb
            alpha=if(e.kind==PageMarkupKind.REDACTION)255 else (e.opacity*255).roundToInt().coerceIn(0,255)
            strokeWidth=max(1f,e.strokeWidth*m); strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND
            style=if(e.filled)Paint.Style.FILL else Paint.Style.STROKE
        }
        when(e.kind){
            PageMarkupKind.REDACTION->{p.style=Paint.Style.FILL;p.color=Color.BLACK;p.alpha=255;c.drawRect(r,p)}
            PageMarkupKind.HIGHLIGHT->{p.style=Paint.Style.FILL;p.alpha=(e.opacity.coerceAtMost(.55f)*255).roundToInt().coerceIn(16,140);c.drawRect(r,p)}
            PageMarkupKind.FREEHAND,PageMarkupKind.SIGNATURE->{
                if(e.points.size<2)return
                val path=Path(); e.points.forEachIndexed{i,q->if(i==0)path.moveTo(q.x*w,q.y*h)else path.lineTo(q.x*w,q.y*h)}
                p.style=Paint.Style.STROKE;c.drawPath(path,p)
            }
            PageMarkupKind.RECTANGLE->c.drawRect(r,p)
            PageMarkupKind.ELLIPSE->c.drawOval(r,p)
            PageMarkupKind.ARROW->{
                val s=e.points.getOrNull(0)?:MarkupPoint(e.left,e.top); val z=e.points.getOrNull(1)?:MarkupPoint(e.right,e.bottom)
                val x1=s.x*w; val y1=s.y*h; val x2=z.x*w; val y2=z.y*h
                p.style=Paint.Style.STROKE;c.drawLine(x1,y1,x2,y2,p)
                val a=atan2((y2-y1).toDouble(),(x2-x1).toDouble()); val head=max(8f,p.strokeWidth*4.5f); val sp=.62
                c.drawLine(x2,y2,(x2-head*cos(a-sp)).toFloat(),(y2-head*sin(a-sp)).toFloat(),p)
                c.drawLine(x2,y2,(x2-head*cos(a+sp)).toFloat(),(y2-head*sin(a+sp)).toFloat(),p)
            }
            PageMarkupKind.TEXT->text(c,r,e,p,false)
            PageMarkupKind.STAMP->{p.style=Paint.Style.STROKE;c.drawRect(r,p);text(c,r,e.copy(text=e.text.uppercase()),p,true)}
        }
    }
    private fun text(c:Canvas,r:RectF,e:PageMarkupElement,p:Paint,bold:Boolean){
        val lines=e.text.split("\n").take(12); if(lines.isEmpty())return
        p.style=Paint.Style.FILL;p.typeface=Typeface.create(Typeface.DEFAULT,if(bold)Typeface.BOLD else Typeface.NORMAL)
        val slot=r.height()/lines.size.coerceAtLeast(1)
        lines.forEachIndexed{i,s->if(s.isNotBlank()){
            p.textSize=fit(p,s,r.width().coerceAtLeast(1f),(slot*.72f).coerceAtLeast(7f))
            val fm=p.fontMetrics; val base=r.top+i*slot+(slot-(fm.bottom-fm.top))/2f-fm.top
            p.textAlign=Paint.Align.CENTER;c.drawText(s,r.centerX(),base,p)
        }}
    }
    private fun fit(p:Paint,s:String,maxW:Float,maxS:Float):Float{
        var lo=4f;var hi=max(lo,maxS);repeat(10){val mid=(lo+hi)/2;p.textSize=mid;if(p.measureText(s)<=maxW)lo=mid else hi=mid};return lo
    }
}
