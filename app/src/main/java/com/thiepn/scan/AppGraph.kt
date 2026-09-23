package com.thiepn.scan

import android.content.Context
import com.thiepn.scan.data.FileStore
import com.thiepn.scan.data.OcrEngine
import com.thiepn.scan.data.OcrSearchIndex
import com.thiepn.scan.data.PdfEngine
import com.thiepn.scan.data.PdfPageRasterizer
import com.thiepn.scan.data.ScanDatabase
import com.thiepn.scan.data.ScanRepository
import com.thiepn.scan.data.SecurityVaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database = ScanDatabase.create(appContext)
    private val files = FileStore(appContext)
    private val ocr = OcrEngine(appContext)
    private val searchIndex = OcrSearchIndex(database)
    val vault = SecurityVaultManager(
        context = appContext,
        dao = database.documentDao(),
        files = files,
        scope = scope
    )

    val repository = ScanRepository(
        context = appContext,
        dao = database.documentDao(),
        files = files,
        ocr = ocr,
        rasterizer = PdfPageRasterizer(),
        pdfEngine = PdfEngine(appContext, ocr),
        searchIndex = searchIndex,
        vault = vault,
        appScope = scope
    )
}
