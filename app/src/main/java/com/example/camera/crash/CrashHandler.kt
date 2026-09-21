package com.example.camera.crash

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

object CrashHandler {
    private const val TAG = "CameraCrashHandler"
    private const val PREFS_NAME = "camera_crash_reports"
    private const val KEY_LAST_CRASH = "last_crash_stack_trace"
    private const val KEY_CRASH_DEVICE = "last_crash_device_info"
    private const val KEY_CRASH_TIME = "last_crash_timestamp"

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        val appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(appContext, thread, throwable)
        }
        Log.i(TAG, "Global CrashHandler successfully initialized")
    }

    fun handleUncaughtException(context: Context, thread: Thread?, throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stackTrace = sw.toString()

            val deviceInfo = "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n" +
                    "Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                    "Thread: ${thread?.name ?: "Unknown"}\n"

            val fullReport = "$deviceInfo\n--- EXCEPTION ---\n$stackTrace"

            Log.e(TAG, "CRITICAL ERROR CAUGHT:\n$fullReport")

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LAST_CRASH, fullReport)
                .putString(KEY_CRASH_DEVICE, deviceInfo)
                .putLong(KEY_CRASH_TIME, System.currentTimeMillis())
                .commit()

            // Launch CrashDisplayActivity to display stack trace directly to user
            val intent = Intent(context, CrashDisplayActivity::class.java).apply {
                putExtra(CrashDisplayActivity.EXTRA_CRASH_REPORT, fullReport)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)

            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal failure inside CrashHandler", t)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun getLastCrashReport(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_CRASH, null)
    }

    fun clearLastCrash(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LAST_CRASH).apply()
    }
}
