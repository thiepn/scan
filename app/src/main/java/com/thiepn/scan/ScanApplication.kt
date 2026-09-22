package com.thiepn.scan

import android.app.Application

class ScanApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
