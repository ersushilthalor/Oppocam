package com.example.camera.gallery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast

data class GalleryAppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null
)

object GalleryLauncher {

    private const val TAG = "GalleryLauncher"

    /**
     * Dynamically finds all installed applications capable of viewing photos or videos.
     * Google Photos, OEM galleries, and 3rd party apps will be returned dynamically without
     * hardcoded package names.
     */
    fun getInstalledGalleryApps(context: Context): List<GalleryAppInfo> {
        val pm = context.packageManager
        val myPackage = context.packageName

        val testIntentImage = Intent(Intent.ACTION_VIEW).apply {
            type = "image/*"
        }
        val testIntentVideo = Intent(Intent.ACTION_VIEW).apply {
            type = "video/*"
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else {
            PackageManager.MATCH_DEFAULT_ONLY
        }

        val imageActivities = try {
            pm.queryIntentActivities(testIntentImage, flags)
        } catch (e: Exception) {
            emptyList()
        }

        val videoActivities = try {
            pm.queryIntentActivities(testIntentVideo, flags)
        } catch (e: Exception) {
            emptyList()
        }

        val combined = (imageActivities + videoActivities)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != myPackage }
            .distinctBy { it.packageName }

        return combined.map { info ->
            val label = try {
                info.loadLabel(pm).toString().ifBlank { info.packageName }
            } catch (e: Exception) {
                info.packageName
            }
            val icon = try {
                info.loadIcon(pm)
            } catch (e: Exception) {
                null
            }
            GalleryAppInfo(
                packageName = info.packageName,
                appName = label,
                icon = icon
            )
        }.sortedBy { it.appName.lowercase() }
    }

    /**
     * Opens the captured media in the user's preferred gallery or falls back to system chooser.
     * Guaranteed never to crash if no compatible app is found or permissions change.
     */
    fun openMedia(
        context: Context,
        uri: Uri,
        isVideo: Boolean,
        preferredPackage: String? = null
    ): Boolean {
        val mimeType = if (isVideo) "video/*" else "image/*"

        // 1. Try launching the preferred package if set and still installed
        if (!preferredPackage.isNullOrBlank()) {
            try {
                val directIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    setPackage(preferredPackage)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(directIntent)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Preferred gallery app ($preferredPackage) failed to launch, falling back to chooser", e)
            }
        }

        // 2. Fall back to standard Android system chooser
        return try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(viewIntent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch system chooser for media: $uri", e)
            try {
                Toast.makeText(context, "No gallery app available to view this file", Toast.LENGTH_SHORT).show()
            } catch (ignored: Exception) {}
            false
        }
    }
}
