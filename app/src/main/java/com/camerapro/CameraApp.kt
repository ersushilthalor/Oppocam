package com.camerapro

import android.app.Application
import com.camerapro.camera.crash.CrashHandler

class CameraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
    }
}
