package com.juncehome.lifepo4ble

import android.app.Application
import java.io.File

class BleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val logFile = File(filesDir, "kmf-ble.log")
        logFile.appendText("I/KMF-BLE: BleApplication.onCreate\n")
        AppLog.installAndroidLogger(logFile)
        AppLog.d("BleApplication.onCreate", "KMF-BLE")
    }

    val graph: AppGraph by lazy { AppGraph(this) }
}
