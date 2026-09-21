package com.example.camera.tracking.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Robust MediaStore storage helper.
 * Automatically saves captured 3x tracked photos and videos directly into the
 * device's public Gallery (DCIM/Camera3X) and indexes them immediately so they appear
 * in the system Photos/Gallery app.
 */
object MediaStorageHelper {

    private const val TAG = "MediaStorageHelper"
    private const val SUBFOLDER = "DCIM/Camera3X"

    /**
     * Automatically saves a cropped photo Bitmap to Android MediaStore Gallery (DCIM/Camera3X).
     * Returns the public content URI.
     */
    fun savePhotoToGallery(
        context: Context,
        bitmap: Bitmap,
        aspectRatioLabel: String = "9:16"
    ): Pair<Uri?, String> {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "TRACKED_PHOTO_${timeStamp}.jpg"

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Images.Media.DESCRIPTION, "3x Tracked Photo ($aspectRatioLabel)")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, SUBFOLDER)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    val dcimDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        "Camera3X"
                    ).apply { if (!exists()) mkdirs() }
                    val targetFile = File(dcimDir, fileName)
                    put(MediaStore.Images.Media.DATA, targetFile.absolutePath)
                }
            }

            try {
                val dcimDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera3X"
                )
                if (!dcimDir.exists()) dcimDir.mkdirs()
            } catch (ignored: Exception) {}

            val resolver = context.contentResolver
            val contentUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (contentUri != null) {
                resolver.openOutputStream(contentUri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(contentUri, contentValues, null, null)
                }
                Log.d(TAG, "Photo automatically saved to Gallery: $contentUri")
                return Pair(contentUri, fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore insert failed, falling back to public DCIM directory", e)
        }

        // Fallback for pre-Q or storage permission fallback
        return try {
            val dcimDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "Camera3X"
            ).apply { if (!exists()) mkdirs() }
            val fallbackFile = File(dcimDir, fileName)
            FileOutputStream(fallbackFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
            }
            MediaScannerConnection.scanFile(context, arrayOf(fallbackFile.absolutePath), arrayOf("image/jpeg"), null)
            Pair(Uri.fromFile(fallbackFile), fallbackFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback photo save failed", e)
            Pair(null, "")
        }
    }

    /**
     * Automatically saves a recorded tracked MP4 video to Android MediaStore Gallery (DCIM/Camera3X).
     * Extracts a first-frame thumbnail Bitmap and returns Pair(publicUri, thumbnailBitmap).
     */
    fun saveVideoToGallery(
        context: Context,
        sourceVideoFile: File,
        resolutionLabel: String = "1080p"
    ): Triple<Uri?, Bitmap?, String> {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "TRACKED_VIDEO_${timeStamp}.mp4"

        // Extract crisp first-frame thumbnail
        val thumbnail = extractVideoThumbnail(sourceVideoFile)

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Video.Media.DESCRIPTION, "3x Tracked Video ($resolutionLabel)")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, SUBFOLDER)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    val dcimDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        "Camera3X"
                    ).apply { if (!exists()) mkdirs() }
                    val targetFile = File(dcimDir, fileName)
                    put(MediaStore.Video.Media.DATA, targetFile.absolutePath)
                }
            }

            try {
                val dcimDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera3X"
                )
                if (!dcimDir.exists()) dcimDir.mkdirs()
            } catch (ignored: Exception) {}

            val resolver = context.contentResolver
            val contentUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (contentUri != null) {
                resolver.openOutputStream(contentUri)?.use { outStream ->
                    FileInputStream(sourceVideoFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(contentUri, contentValues, null, null)
                }
                Log.d(TAG, "Video automatically saved to Gallery: $contentUri")
                return Triple(contentUri, thumbnail, fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore video insert failed, attempting fallback copy", e)
        }

        // Fallback for pre-Q or storage permission fallback
        return try {
            val dcimDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "Camera3X"
            ).apply { if (!exists()) mkdirs() }
            val fallbackFile = File(dcimDir, fileName)
            sourceVideoFile.copyTo(fallbackFile, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(fallbackFile.absolutePath), arrayOf("video/mp4"), null)
            Triple(Uri.fromFile(fallbackFile), thumbnail, fallbackFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback video save failed", e)
            Triple(Uri.fromFile(sourceVideoFile), thumbnail, sourceVideoFile.absolutePath)
        }
    }

    private fun extractVideoThumbnail(file: File): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            retriever.release()
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract video thumbnail: ${e.message}")
            null
        }
    }
}
