package com.juncehome.lifepo4ble

import android.app.Application

class BleApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
