package com.example

import android.app.Application
import com.example.camera.crash.CrashHandler

class CameraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
    }
}
