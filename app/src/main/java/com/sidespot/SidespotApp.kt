package com.sidespot

import android.app.Application
import com.sidespot.bridge.NativeBridge

class SidespotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeBridge.init()
        NativeBridge.setTmpDir(cacheDir.absolutePath)
    }
}
