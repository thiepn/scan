package com.thiepn.scan

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ScanApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        runBlocking(Dispatchers.IO) {
            graph.vault.sealRegisteredOnColdStart()
        }
        graph.repository.resumePendingProcessing()
    }
}
