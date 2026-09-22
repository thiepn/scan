package com.thiepn.scan

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class ScanApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        graph.repository.resumePendingProcessing()
    }
}
