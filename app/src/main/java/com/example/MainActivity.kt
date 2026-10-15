package com.example
 
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.example.camera.crash.CrashHandler
import com.example.camera.ui.CameraScreen
import com.example.camera.viewmodel.CameraViewModel
import com.example.ui.theme.MyApplicationTheme
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_CRASH_REPORT = "extra_crash_report"
    }

    private var cameraViewModel: CameraViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler.init(this)

        try {
            enableEdgeToEdge()
        } catch (t: Throwable) {
            Log.w(TAG, "Edge-to-edge configuration warning", t)
        }

        val crashReport = intent?.getStringExtra(EXTRA_CRASH_REPORT)
            ?: CrashHandler.getLastCrashReport(this)

        if (!crashReport.isNullOrEmpty()) {
            showCrashReport(crashReport)
            return
        }

        try {
            val vm = ViewModelProvider(this)[CameraViewModel::class.java]
            cameraViewModel = vm

            setContent {
                MyApplicationTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Black
                    ) {
                        CameraScreen(viewModel = vm)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error in MainActivity.onCreate", t)
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            t.printStackTrace(pw)
            showCrashReport("Fatal initialization error:\n${sw}")
        }
    }

    private fun showCrashReport(report: String) {
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF121214),
                    surface = Color(0xFF1E1E24),
                    primary = Color(0xFFFF5252),
                    onPrimary = Color.White,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121214)
                ) {
                    CrashScreen(
                        crashReport = report,
                        onRestart = {
                            CrashHandler.clearLastCrash(this@MainActivity)
                            val restartIntent = Intent(this@MainActivity, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                            startActivity(restartIntent)
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val vm = cameraViewModel
            if (vm != null && vm.handleVolumeKeyDown(keyCode, event)) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val vm = cameraViewModel
            if (vm != null && vm.handleVolumeKeyUp(keyCode, event)) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}

@Composable
fun CrashScreen(
    crashReport: String,
    onRestart: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Crash Icon",
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(32.dp)
            )
            Column {
                Text(
                    text = "App Crash Log",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Copy this error stack trace and share it",
                    fontSize = 12.sp,
                    color = Color.LightGray
                )
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Camera Crash Log", crashReport)
                    clipboard.setPrimaryClip(clip)
                    copied = true
                    Toast.makeText(context, "Stack trace copied to clipboard!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (copied) "Copied!" else "Copy Log", fontSize = 13.sp)
            }

            Button(
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Camera App Crash Report")
                        putExtra(Intent.EXTRA_TEXT, crashReport)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Crash Report"))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C36)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Share", fontSize = 13.sp, color = Color.White)
            }

            Button(
                onClick = onRestart,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Restart", fontSize = 13.sp)
            }
        }

        // Stack Trace Box
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0A0A0C))
                .padding(12.dp)
        ) {
            val verticalScrollState = rememberScrollState()
            val horizontalScrollState = rememberScrollState()

            Text(
                text = crashReport,
                color = Color(0xFFFF8A80),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScrollState)
                    .horizontalScroll(horizontalScrollState)
            )
        }
    }
}

