package com.thiepn.scan.data

import java.security.MessageDigest
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class StructuredBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun normalized(): StructuredBox {
        val l=min(left,right).coerceIn(0f,1f)
        val r=max(left,right).coerceIn(0f,1f)
        val t=min(top,bottom).coerceIn(0f,1f)
        val b=max(top,bottom).coerceIn(0f,1f)
        return StructuredBox(l,t,r,b)
    }
}

data class StructuredCell(
    val row: Int,
    val column: Int,
    val text: String,
    val confidence: Float,
    val reviewed: Boolean = false,
    val box: StructuredBox? = null
) {
    fun normalized()=copy(
        row=row.coerceAtLeast(0),
        column=column.coerceAtLeast(0),
        text=text.take(4000),
        confidence=confidence.coerceIn(0f,1f),
        box=box?.normalized()
    )
}

data class StructuredTable(
    val id: String,
    val title: String,
    val rowCount: Int,
    val columnCount: Int,
    val cells: List<StructuredCell>,
    val confidence: Float,
    val reviewed: Boolean = false,
    val source: String = "OCR"
) {
    fun normalized():StructuredTable {
        val normalizedCells=cells.map(StructuredCell::normalized)
            .filter{it.row<1000&&it.column<100}
            .take(5000)
        val rows=max(rowCount,normalizedCells.maxOfOrNull{it.row+1}?:0)
        val cols=max(columnCount,normalizedCells.maxOfOrNull{it.column+1}?:0)
        return copy(
            title=title.trim().take(160),
            rowCount=rows.coerceIn(0,1000),
            columnCount=cols.coerceIn(0,100),
            cells=normalizedCells,
            confidence=confidence.coerceIn(0f,1f)
        )
    }
    fun cell(row:Int,column:Int)=cells.firstOrNull{it.row==row&&it.column==column}
    fun reviewCount()=cells.count{!it.reviewed&&it.confidence<0.82f}+
        if(!reviewed&&confidence<0.78f)1 else 0
}

data class StructuredKeyValue(
    val id: String,
    val key: String,
    val label: String,
    val value: String,
    val confidence: Float,
    val reviewed: Boolean = false,
    val source: String = "OCR",
    val box: StructuredBox? = null
) {
    fun normalized()=copy(
        key=normalizeKey(key.ifBlank{label}),
        label=label.trim().take(180),
        value=value.take(4000),
        confidence=confidence.coerceIn(0f,1f),
        box=box?.normalized()
    )
    companion object {
        fun normalizeKey(value:String):String=value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"),"_")
            .trim('_')
            .take(80)
            .ifBlank{"field"}
    }
}

data class PageStructuredData(
    val version:Int=1,
    val sourceToken:String="",
    val tables:List<StructuredTable> = emptyList(),
    val keyValues:List<StructuredKeyValue> = emptyList()
) {
    fun normalized():PageStructuredData=PageStructuredData(
        1,
        sourceToken.take(80),
        tables.map(StructuredTable::normalized).distinctBy{it.id}.take(80),
        keyValues.map(StructuredKeyValue::normalized).distinctBy{it.id}.take(500)
    )
    fun isEmpty()=tables.isEmpty()&&keyValues.isEmpty()
    fun reviewCount()=
        tables.sumOf(StructuredTable::reviewCount)+
            keyValues.count{!it.reviewed&&it.confidence<0.82f}
    fun isStale(result:OcrPageResult?):Boolean=
        result!=null&&sourceToken.isNotBlank()&&sourceToken!=StructuredSourceToken.create(result)
}

object StructuredSourceToken {
    fun create(result:OcrPageResult):String {
        val digest=MessageDigest.getInstance("SHA-256")
        val payload=OcrLayoutCodec.encode(result).toByteArray(Charsets.UTF_8)
        return digest.digest(payload).joinToString(""){"%02x".format(it)}
    }
}

object PageStructuredDataCodec {
    private const val F="\t"
    private const val R="\n"
    fun encode(data:PageStructuredData?):String? {
        val n=data?.normalized()?:return null
        if(n.isEmpty())return null
        return buildList {
            add(listOf("H","1",enc(n.sourceToken)).joinToString(F))
            n.keyValues.forEach { x ->
                add(listOf(
                    "K",enc(x.id),enc(x.key),enc(x.label),enc(x.value),
                    x.confidence,if(x.reviewed)1 else 0,enc(x.source),box(x.box)
                ).joinToString(F))
            }
            n.tables.forEach { t ->
                add(listOf(
                    "T",enc(t.id),enc(t.title),t.rowCount,t.columnCount,t.confidence,
                    if(t.reviewed)1 else 0,enc(t.source)
                ).joinToString(F))
                t.cells.forEach { c ->
                    add(listOf(
                        "C",enc(t.id),c.row,c.column,enc(c.text),c.confidence,
                        if(c.reviewed)1 else 0,box(c.box)
                    ).joinToString(F))
                }
            }
        }.joinToString(R)
    }
    fun decode(encoded:String?):PageStructuredData {
        if(encoded.isNullOrBlank())return PageStructuredData()
        val rows=encoded.split(R)
        val h=rows.firstOrNull()?.split(F).orEmpty()
        if(h.size!=3||h[0]!="H"||h[1]!="1")return PageStructuredData()
        val source=dec(h[2])
        val keys=mutableListOf<StructuredKeyValue>()
        data class Meta(
            val id:String,val title:String,val rows:Int,val cols:Int,
            val confidence:Float,val reviewed:Boolean,val source:String
        )
        val metas=LinkedHashMap<String,Meta>()
        val cells=LinkedHashMap<String,MutableList<StructuredCell>>()
        rows.drop(1).forEach { row ->
            val p=row.split(F)
            when(p.firstOrNull()) {
                "K" -> if(p.size==9) runCatching {
                    keys+=StructuredKeyValue(
                        id=dec(p[1]),key=dec(p[2]),label=dec(p[3]),value=dec(p[4]),
                        confidence=p[5].toFloat(),reviewed=p[6]=="1",
                        source=dec(p[7]),box=parseBox(p[8])
                    )
                }
                "T" -> if(p.size==8) runCatching {
                    val id=dec(p[1])
                    metas[id]=Meta(id,dec(p[2]),p[3].toInt(),p[4].toInt(),
                        p[5].toFloat(),p[6]=="1",dec(p[7]))
                }
                "C" -> if(p.size==8) runCatching {
                    val id=dec(p[1])
                    cells.getOrPut(id){mutableListOf()}+=StructuredCell(
                        row=p[2].toInt(),column=p[3].toInt(),text=dec(p[4]),
                        confidence=p[5].toFloat(),reviewed=p[6]=="1",box=parseBox(p[7])
                    )
                }
            }
        }
        val tables=metas.values.map { m ->
            StructuredTable(
                m.id,m.title,m.rows,m.cols,cells[m.id].orEmpty(),
                m.confidence,m.reviewed,m.source
            )
        }
        return PageStructuredData(1,source,tables,keys).normalized()
    }
    private fun enc(s:String)=if(s.isEmpty())"~" else Base64.getUrlEncoder()
        .withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun dec(s:String)=if(s=="~")"" else runCatching {
        Base64.getUrlDecoder().decode(s).toString(Charsets.UTF_8)
    }.getOrDefault("")
    private fun box(b:StructuredBox?)=b?.let{"${it.left},${it.top},${it.right},${it.bottom}"}?:"~"
    private fun parseBox(s:String):StructuredBox? {
        if(s=="~")return null
        val p=s.split(",");if(p.size!=4)return null
        val v=p.map{it.toFloatOrNull()?:return null}
        return StructuredBox(v[0],v[1],v[2],v[3]).normalized()
    }
}

data class ExtractionSchemaField(
    val key:String,
    val label:String,
    val aliases:List<String> = emptyList()
)

data class ExtractionSchemaTable(
    val title:String,
    val headers:List<String>
)

data class ExtractionSchemaDefinition(
    val version:Int=1,
    val fields:List<ExtractionSchemaField> = emptyList(),
    val tables:List<ExtractionSchemaTable> = emptyList()
)

object ExtractionSchemaCodec {
    private const val F="\t"
    fun encode(schema:ExtractionSchemaDefinition):String=
        buildList {
            add("H\t1")
            schema.fields.distinctBy{it.key}.forEach { x ->
                add(listOf("F",enc(x.key),enc(x.label),enc(x.aliases.joinToString("\u001f"))).joinToString(F))
            }
            schema.tables.forEach { x ->
                add(listOf("T",enc(x.title),enc(x.headers.joinToString("\u001f"))).joinToString(F))
            }
        }.joinToString("\n")
    fun decode(encoded:String?):ExtractionSchemaDefinition? {
        if(encoded.isNullOrBlank())return null
        val rows=encoded.split("\n")
        if(rows.firstOrNull()!="H\t1")return null
        val fields=mutableListOf<ExtractionSchemaField>()
        val tables=mutableListOf<ExtractionSchemaTable>()
        rows.drop(1).forEach { row ->
            val p=row.split(F)
            when(p.firstOrNull()) {
                "F" -> if(p.size==4) fields+=ExtractionSchemaField(
                    dec(p[1]),dec(p[2]),dec(p[3]).split("\u001f").filter{it.isNotBlank()}
                )
                "T" -> if(p.size==3) tables+=ExtractionSchemaTable(
                    dec(p[1]),dec(p[2]).split("\u001f").filter{it.isNotBlank()}
                )
            }
        }
        return ExtractionSchemaDefinition(fields=fields,tables=tables)
    }
    fun fromPages(pages:List<PageStructuredData>):ExtractionSchemaDefinition {
        val fields=pages.flatMap{it.keyValues}.map {
            ExtractionSchemaField(it.key,it.label,listOf(it.label,it.key))
        }.distinctBy{it.key}
        val tables=pages.flatMap{it.tables}.mapNotNull { table ->
            if(table.columnCount<=0)return@mapNotNull null
            val headers=(0 until table.columnCount).map { col ->
                table.cell(0,col)?.text?.takeIf{it.isNotBlank()} ?: "Column ${col+1}"
            }
            ExtractionSchemaTable(table.title,headers)
        }
        return ExtractionSchemaDefinition(fields=fields,tables=tables)
    }
    private fun enc(s:String)=if(s.isEmpty())"~" else Base64.getUrlEncoder()
        .withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun dec(s:String)=if(s=="~")"" else runCatching {
        Base64.getUrlDecoder().decode(s).toString(Charsets.UTF_8)
    }.getOrDefault("")
}

object StructuredSchemaMatcher {
    fun apply(data:PageStructuredData,schema:ExtractionSchemaDefinition?):PageStructuredData {
        schema?:return data
        val existing=data.keyValues.toMutableList()
        schema.fields.forEach { target ->
            val names=(target.aliases+target.label+target.key).map(::norm).filter{it.isNotBlank()}.toSet()
            val index=existing.indexOfFirst { norm(it.label) in names || norm(it.key) in names }
            if(index>=0) {
                existing[index]=existing[index].copy(key=target.key,label=target.label)
            } else {
                existing+=StructuredKeyValue(
                    id="S:${target.key}",key=target.key,label=target.label,value="",
                    confidence=0f,reviewed=false,source="SCHEMA"
                )
            }
        }
        val tables=data.tables.toMutableList()
        schema.tables.forEachIndexed { index,target ->
            val found=tables.indexOfFirst { it.columnCount==target.headers.size &&
                (norm(it.title)==norm(target.title)||headerSimilarity(it,target.headers)>=0.45f) }
            if(found>=0) {
                tables[found]=tables[found].copy(title=target.title)
            } else if(target.headers.isNotEmpty()) {
                tables+=StructuredTable(
                    id="S:T:$index",title=target.title,rowCount=1,
                    columnCount=target.headers.size,
                    cells=target.headers.mapIndexed { col,h ->
                        StructuredCell(0,col,h,0f,false,null)
                    },
                    confidence=0f,reviewed=false,source="SCHEMA"
                )
            }
        }
        return data.copy(tables=tables,keyValues=existing).normalized()
    }
    private fun norm(s:String)=s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    private fun headerSimilarity(table:StructuredTable,headers:List<String>):Float {
        if(headers.isEmpty()||table.columnCount!=headers.size)return 0f
        var matches=0
        headers.forEachIndexed { i,h ->
            val cell=norm(table.cell(0,i)?.text.orEmpty())
            val header=norm(h)
            if(
                cell.isNotBlank() &&
                header.isNotBlank() &&
                (cell==header||cell.contains(header)||header.contains(cell))
            ) matches++
        }
        return matches.toFloat()/headers.size
    }
}

object StructuredDataDetector {
    private val moneyRegex=Regex("""(?i)(?:EUR|USD|GBP|€|\$|£)?\s*-?\d{1,9}(?:[.,]\d{2})\s*(?:EUR|USD|GBP|€|\$|£)?""")
    private val knownLabels=linkedMapOf(
        "invoice_number" to listOf("invoice no","invoice number","rechnung nr","rechnungsnummer"),
        "date" to listOf("date","datum","invoice date","rechnungsdatum"),
        "due_date" to listOf("due date","payment due","fällig","faellig"),
        "subtotal" to listOf("subtotal","net total","zwischensumme","netto"),
        "tax" to listOf("tax","vat","mwst","umsatzsteuer"),
        "total" to listOf("total","amount due","grand total","gesamt","summe"),
        "customer" to listOf("customer","client","kunde"),
        "vendor" to listOf("vendor","supplier","merchant","lieferant")
    )

    fun detect(
        result:OcrPageResult?,
        mode:ScanMode=ScanMode.DOCUMENT,
        schema:ExtractionSchemaDefinition?=null
    ):PageStructuredData {
        result?:return PageStructuredData()
        if(result.sourceWidth<=0||result.sourceHeight<=0)return PageStructuredData()
        val tables=detectTables(result).toMutableList()
        if((mode==ScanMode.RECEIPT||mode==ScanMode.FORM||mode==ScanMode.DOCUMENT)&&
            tables.none{it.columnCount>=2&&it.rowCount>=2}) {
            detectLineItems(result)?.let(tables::add)
        }
        val keys=detectKeyValues(result,mode)
        return StructuredSchemaMatcher.apply(
            PageStructuredData(
                sourceToken=StructuredSourceToken.create(result),
                tables=tables,
                keyValues=keys
            ).normalized(),
            schema
        )
    }

    private data class CellDraft(
        val words:List<OcrWordBox>,
        val left:Int,val top:Int,val right:Int,val bottom:Int
    ) {
        val text get()=words.joinToString(" "){it.text}
        val confidence get()=words.map{it.confidence}.average().toFloat()
        val center get()=(left+right)/2f
    }
    private data class RowDraft(
        val block:Int,val line:Int,val top:Int,val bottom:Int,
        val cells:List<CellDraft>
    )

    private fun detectTables(result:OcrPageResult):List<StructuredTable> {
        val width=result.sourceWidth
        val grouped=result.words.groupBy{it.blockIndex to it.lineIndex}
        val rows=grouped.values.mapNotNull { words ->
            val sorted=words.filter{it.text.isNotBlank()}.sortedBy{it.left}
            if(sorted.size<2)return@mapNotNull null
            val medianHeight=sorted.map{(it.bottom-it.top).coerceAtLeast(1)}
                .sorted().let{it[it.size/2]}
            val threshold=max(width*.035f,medianHeight*1.8f)
            val groups=mutableListOf<MutableList<OcrWordBox>>(mutableListOf(sorted.first()))
            sorted.zipWithNext().forEach { (a,b) ->
                if(b.left-a.right>threshold) groups+=mutableListOf(b) else groups.last()+=b
            }
            if(groups.size !in 2..10)return@mapNotNull null
            val cells=groups.map { g ->
                CellDraft(g,g.minOf{it.left},g.minOf{it.top},g.maxOf{it.right},g.maxOf{it.bottom})
            }
            val span=cells.last().right-cells.first().left
            if(span<width*.30f)return@mapNotNull null
            RowDraft(
                sorted.first().blockIndex,sorted.first().lineIndex,
                sorted.minOf{it.top},sorted.maxOf{it.bottom},cells
            )
        }.sortedBy{it.top}

        val runs=mutableListOf<MutableList<RowDraft>>()
        rows.forEach { row ->
            val last=runs.lastOrNull()
            if(last==null) {
                runs+=mutableListOf(row)
            } else {
                val prev=last.last()
                val gap=row.top-prev.bottom
                val avgHeight=((prev.bottom-prev.top)+(row.bottom-row.top))/2f
                val aligned=prev.cells.size==row.cells.size&&columnsAligned(prev,row,result.sourceWidth)
                if(aligned&&gap<=avgHeight*2.8f) last+=row else runs+=mutableListOf(row)
            }
        }

        var index=0
        return runs.mapNotNull { run ->
            if(run.size<2)return@mapNotNull null
            val cols=run.first().cells.size
            if(run.any{it.cells.size!=cols})return@mapNotNull null
            val confidence=run.flatMap{it.cells}.map{it.confidence}.average().toFloat()
            val tableId="T:${run.first().block}:${run.first().line}:${index++}"
            val cells=run.flatMapIndexed { r,row ->
                row.cells.mapIndexed { c,cell ->
                    StructuredCell(
                        r,c,cell.text,cell.confidence,false,
                        StructuredBox(
                            cell.left/result.sourceWidth.toFloat(),
                            cell.top/result.sourceHeight.toFloat(),
                            cell.right/result.sourceWidth.toFloat(),
                            cell.bottom/result.sourceHeight.toFloat()
                        )
                    )
                }
            }
            StructuredTable(
                id=tableId,title="Table $index",rowCount=run.size,columnCount=cols,
                cells=cells,confidence=confidence,reviewed=false,source="OCR"
            )
        }
    }

    private fun columnsAligned(a:RowDraft,b:RowDraft,width:Int):Boolean {
        if(a.cells.size!=b.cells.size)return false
        val tolerance=width*.075f
        val matches=a.cells.indices.count { i ->
            abs(a.cells[i].center-b.cells[i].center)<=tolerance
        }
        val required=if(a.cells.size<=2) {
            a.cells.size
        } else {
            max(2,(a.cells.size*2+2)/3)
        }
        return matches>=required
    }

    private fun detectLineItems(result:OcrPageResult):StructuredTable? {
        val rows=result.lines.mapNotNull { line ->
            val lower=line.text.lowercase()
            if(listOf("total","subtotal","tax","vat","mwst","amount due").any{it in lower})return@mapNotNull null
            val match=moneyRegex.findAll(line.text).lastOrNull()?:return@mapNotNull null
            val description=line.text.substring(0,match.range.first).trim(' ','-','·','.')
            if(description.length<2)return@mapNotNull null
            Triple(description,match.value.trim(),line)
        }
        if(rows.size<2)return null
        val cells=mutableListOf(
            StructuredCell(0,0,"Item",.72f,false,null),
            StructuredCell(0,1,"Amount",.72f,false,null)
        )
        rows.take(100).forEachIndexed { i,(description,amount,line) ->
            val box=StructuredBox(
                line.left/result.sourceWidth.toFloat(),line.top/result.sourceHeight.toFloat(),
                line.right/result.sourceWidth.toFloat(),line.bottom/result.sourceHeight.toFloat()
            )
            cells+=StructuredCell(i+1,0,description,line.confidence,false,box)
            cells+=StructuredCell(i+1,1,amount,line.confidence,false,box)
        }
        return StructuredTable(
            id="LINE_ITEMS",title="Line items",rowCount=rows.size+1,columnCount=2,
            cells=cells,confidence=rows.map{it.third.confidence}.average().toFloat(),
            reviewed=false,source="LINE_ITEMS"
        )
    }

    private fun detectKeyValues(result:OcrPageResult,mode:ScanMode):List<StructuredKeyValue> {
        val out=LinkedHashMap<String,StructuredKeyValue>()
        result.lines.sortedBy{it.readingOrder}.forEach { line ->
            val raw=line.text.trim()
            val colon=raw.indexOf(':')
            if(colon in 1..50&&colon<raw.lastIndex&&"://" !in raw) {
                val label=raw.substring(0,colon).trim()
                val value=raw.substring(colon+1).trim()
                if(
                    label.length>=2 &&
                    label.any{it.isLetter()} &&
                    value.isNotBlank()
                ) {
                    val key=StructuredKeyValue.normalizeKey(label)
                    out.putIfAbsent(key,keyValue(key,label,value,line,result,"OCR"))
                }
            }
            val lower=raw.lowercase()
            knownLabels.forEach { (key,aliases) ->
                val alias=aliases.firstOrNull { a ->
                    lower==a||lower.startsWith(a+":")||lower.startsWith(a+" ")
                }?:return@forEach
                val value=raw.substring(alias.length).trim(' ',':','-','–')
                if(value.isNotBlank()) {
                    val label=aliases.first()
                    val current=out[key]
                    val candidate=keyValue(key,label,value,line,result,"KNOWN")
                    if(current==null||candidate.confidence>current.confidence)out[key]=candidate
                }
            }
        }

        SpecializedFieldExtractor.extract(mode,"",result.text).forEach { field ->
            val key=StructuredKeyValue.normalizeKey(field.key)
            out.putIfAbsent(
                key,
                StructuredKeyValue(
                    id="E:$key",key=key,label=field.label,value=field.value,
                    confidence=field.confidence,reviewed=false,source="SPECIALIZED",box=null
                )
            )
        }
        return out.values.toList()
    }

    private fun keyValue(
        key:String,label:String,value:String,line:OcrTextLine,
        result:OcrPageResult,source:String
    )=StructuredKeyValue(
        id="K:$key:${line.blockIndex}:${line.lineIndex}",
        key=key,label=label,value=value,confidence=line.confidence,
        reviewed=false,source=source,
        box=StructuredBox(
            line.left/result.sourceWidth.toFloat(),line.top/result.sourceHeight.toFloat(),
            line.right/result.sourceWidth.toFloat(),line.bottom/result.sourceHeight.toFloat()
        )
    )
}
