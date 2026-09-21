package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.camera.crash.CrashHandler
import com.example.camera.ui.CameraScreen
import com.example.camera.viewmodel.CameraViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler.init(this)

        try {
            enableEdgeToEdge()
        } catch (t: Throwable) {
            Log.w(TAG, "Edge-to-edge configuration warning", t)
        }

        try {
            setContent {
                MyApplicationTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Black
                    ) {
                        val cameraViewModel: CameraViewModel = viewModel()
                        CameraScreen(viewModel = cameraViewModel)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error in MainActivity.onCreate", t)
            CrashHandler.handleUncaughtException(this, Thread.currentThread(), t)
        }
    }
}

