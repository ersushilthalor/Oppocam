package com.example.camera.data

import android.content.Context
import android.content.ContentUris
import android.provider.MediaStore
import android.media.ExifInterface
import com.example.camera.data.db.AppDatabase
import com.example.camera.data.db.RefocusPhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository managing persistent Refocus photo packages and database records.
 */
class RefocusRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.refocusDao()

    val allRefocusPhotos: Flow<List<RefocusPhotoEntity>> = dao.getAllRefocusPhotos()

    suspend fun getRefocusPhoto(photoUri: String): RefocusPhotoEntity? = withContext(Dispatchers.IO) {
        // 1. Exact match in Room database
        val entity = dao.getRefocusPhoto(photoUri)
        if (entity != null && File(entity.bundleDir).exists()) {
            return@withContext entity
        }

        val cleanId = photoUri.substringAfterLast("/")

        // 2. Partial / ID-matching in Room database
        try {
            val allEntities = dao.getAllRefocusPhotosList()
            val matched = allEntities.firstOrNull {
                it.photoUri == photoUri ||
                it.photoUri.substringAfterLast("/") == cleanId ||
                (cleanId.isNotBlank() && it.photoUri.contains(cleanId))
            }
            if (matched != null && File(matched.bundleDir).exists()) {
                if (matched.photoUri != photoUri) {
                    val updated = matched.copy(photoUri = photoUri)
                    dao.insertRefocusPhoto(updated)
                    return@withContext updated
                }
                return@withContext matched
            }
        } catch (e: Exception) {
            // Ignore DB query errors
        }

        // 3. Check refocus storage bundles directly via metadata.json
        try {
            val bundlesDir = getRefocusStorageDir()
            val bundleDirs = bundlesDir.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() }
            if (bundleDirs != null) {
                for (bDir in bundleDirs) {
                    val metaFile = File(bDir, "metadata.json")
                    if (metaFile.exists()) {
                        try {
                            val json = org.json.JSONObject(metaFile.readText())
                            val savedUri = json.optString("photoUri", "")
                            if (savedUri == photoUri || (cleanId.isNotBlank() && savedUri.substringAfterLast("/") == cleanId)) {
                                val near = File(bDir, "plane_near.jpg")
                                val mid = File(bDir, "plane_mid.jpg")
                                val far = File(bDir, "plane_far.jpg")
                                val depth = File(bDir, "depth_map.png")
                                val count = json.optInt("planeCount", 3)
                                val recovered = RefocusPhotoEntity(
                                    photoUri = photoUri,
                                    bundleDir = bDir.absolutePath,
                                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                                    nearPlanePath = near.absolutePath,
                                    midPlanePath = mid.absolutePath,
                                    farPlanePath = far.absolutePath,
                                    depthMapPath = if (depth.exists()) depth.absolutePath else "",
                                    planeCount = count,
                                    nearDiopters = json.optDouble("nearDiopters", 0.0).toFloat(),
                                    midDiopters = json.optDouble("midDiopters", 0.0).toFloat(),
                                    farDiopters = json.optDouble("farDiopters", 0.0).toFloat(),
                                    width = json.optInt("width", 0),
                                    height = json.optInt("height", 0)
                                )
                                dao.insertRefocusPhoto(recovered)
                                return@withContext recovered
                            }
                        } catch (e: Exception) {
                            // Continue searching other bundles
                        }
                    }
                }

                // 4. If taken in the last 20 seconds, match the newest bundle directory
                val newest = bundleDirs.firstOrNull()
                if (newest != null && System.currentTimeMillis() - newest.lastModified() < 20000) {
                    val near = File(newest, "plane_near.jpg")
                    val mid = File(newest, "plane_mid.jpg")
                    val far = File(newest, "plane_far.jpg")
                    val depth = File(newest, "depth_map.png")
                    if (near.exists() && mid.exists() && far.exists()) {
                        val recovered = RefocusPhotoEntity(
                            photoUri = photoUri,
                            bundleDir = newest.absolutePath,
                            timestamp = newest.lastModified(),
                            nearPlanePath = near.absolutePath,
                            midPlanePath = mid.absolutePath,
                            farPlanePath = far.absolutePath,
                            depthMapPath = if (depth.exists()) depth.absolutePath else "",
                            planeCount = 5
                        )
                        dao.insertRefocusPhoto(recovered)
                        return@withContext recovered
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore fallback lookup errors
        }

        null
    }


    /**
     * Re-indexes valid bundles on disk so the built-in gallery can recover Refocus captures
     * even if a database record was lost or the app was upgraded from an older build.
     */
    suspend fun discoverStoredRefocusPhotos() = withContext(Dispatchers.IO) {
        val bundles = getRefocusStorageDir().listFiles { file -> file.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        for (bundle in bundles) {
            try {
                val metadata = File(bundle, "metadata.json")
                if (!metadata.exists()) continue
                val json = org.json.JSONObject(metadata.readText())
                val near = File(bundle, "plane_near.jpg")
                val mid = File(bundle, "plane_mid.jpg")
                val far = File(bundle, "plane_far.jpg")
                if (!near.exists() || !mid.exists() || !far.exists()) continue

                val storedUri = json.optString("photoUri", "").ifBlank { "refocus://${bundle.name}" }
                val planeCount = json.optInt("planeCount", 3).coerceIn(3, 20)
                val entity = RefocusPhotoEntity(
                    photoUri = storedUri,
                    bundleDir = bundle.absolutePath,
                    timestamp = json.optLong("timestamp", bundle.lastModified()),
                    nearPlanePath = near.absolutePath,
                    midPlanePath = mid.absolutePath,
                    farPlanePath = far.absolutePath,
                    depthMapPath = File(bundle, "depth_map.png").takeIf { it.exists() }?.absolutePath.orEmpty(),
                    planeCount = planeCount,
                    nearDiopters = json.optDouble("nearDiopters", 0.0).toFloat(),
                    midDiopters = json.optDouble("midDiopters", 0.0).toFloat(),
                    farDiopters = json.optDouble("farDiopters", 0.0).toFloat(),
                    width = json.optInt("width", 0),
                    height = json.optInt("height", 0)
                )
                dao.insertRefocusPhoto(entity)
            } catch (_: Exception) {
                // Ignore incomplete/corrupt bundles and keep indexing the rest.
            }
        }
    }

    /**
     * Matches Refocus JPEGs in the device media library to their companion focus-plane bundles.
     * New captures carry both a REFOCUS_ filename and an EXIF marker. Legacy captures without
     * a marker are recovered by matching their MediaStore image ID to the URI in bundle metadata.
     */
    suspend fun discoverDeviceRefocusPhotos() = withContext(Dispatchers.IO) {
        try {
            val bundles = getRefocusStorageDir().listFiles { file -> file.isDirectory }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            val metadataByBundle = bundles.mapNotNull { dir ->
                runCatching {
                    val metadataFile = File(dir, "metadata.json")
                    if (!metadataFile.exists()) return@runCatching null
                    dir to org.json.JSONObject(metadataFile.readText())
                }.getOrNull()
            }
            val existing = dao.getAllRefocusPhotosList()
            val resolver = context.contentResolver
            val columns = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                columns,
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val imageId = cursor.getLong(idColumn)
                    val displayName = cursor.getString(nameColumn).orEmpty()
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)
                    val imageIdText = imageId.toString()
                    val legacyMatch = metadataByBundle.firstOrNull { (_, json) ->
                        val storedUri = json.optString("photoUri", "")
                        storedUri.substringBefore('?').substringAfterLast('/') == imageIdText
                    }
                    // Avoid decoding EXIF for every ordinary photo. New Refocus captures use a
                    // dedicated filename; old captures are inspected only when a bundle URI matches.
                    if (!displayName.startsWith("REFOCUS_", ignoreCase = true) && legacyMatch == null) {
                        continue
                    }

                    val marker = runCatching {
                        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                            val exif = ExifInterface(descriptor.fileDescriptor)
                            exif.getAttribute(ExifInterface.TAG_USER_COMMENT).orEmpty()
                                .ifBlank { exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty() }
                                .substringBefore(";")
                        }.orEmpty()
                    }.getOrDefault("")

                    val markedBundleId = marker.takeIf { it.startsWith("OPPOCAM_REFOCUS_V1:") }
                        ?.substringAfter("OPPOCAM_REFOCUS_V1:")
                    val matched = metadataByBundle.firstOrNull { (dir, json) ->
                        (markedBundleId != null && (dir.name == markedBundleId ||
                            json.optString("refocusId", "") == markedBundleId)) ||
                            (legacyMatch != null && dir.absolutePath == legacyMatch.first.absolutePath)
                    } ?: continue

                    val (bundle, json) = matched
                    val near = File(bundle, "plane_near.jpg")
                    val mid = File(bundle, "plane_mid.jpg")
                    val far = File(bundle, "plane_far.jpg")
                    if (!near.exists() || !mid.exists() || !far.exists()) continue

                    // Older bundles can store an equivalent external_primary URI while the
                    // current MediaStore query returns external. Replace that alias instead
                    // of displaying the same Refocus photo twice.
                    existing.filter { stored ->
                        stored.bundleDir == bundle.absolutePath &&
                            stored.photoUri != uri.toString() &&
                            stored.photoUri.substringBefore('?').substringAfterLast('/') == imageIdText
                    }.forEach { dao.deleteRefocusPhoto(it.photoUri) }

                    val entity = RefocusPhotoEntity(
                        photoUri = uri.toString(),
                        bundleDir = bundle.absolutePath,
                        timestamp = json.optLong("timestamp", cursor.getLong(dateColumn).takeIf { it > 0L } ?: bundle.lastModified()),
                        nearPlanePath = near.absolutePath,
                        midPlanePath = mid.absolutePath,
                        farPlanePath = far.absolutePath,
                        depthMapPath = File(bundle, "depth_map.png").takeIf { it.exists() }?.absolutePath.orEmpty(),
                        planeCount = json.optInt("planeCount", 3).coerceIn(3, 20),
                        nearDiopters = json.optDouble("nearDiopters", 0.0).toFloat(),
                        midDiopters = json.optDouble("midDiopters", 0.0).toFloat(),
                        farDiopters = json.optDouble("farDiopters", 0.0).toFloat(),
                        width = json.optInt("width", cursor.getInt(widthColumn)),
                        height = json.optInt("height", cursor.getInt(heightColumn))
                    )
                    dao.insertRefocusPhoto(entity)
                }
            }
        } catch (e: SecurityException) {
            android.util.Log.w("RefocusRepository", "Media access permission not granted; internal Refocus bundles can still be shown", e)
        } catch (e: Exception) {
            android.util.Log.w("RefocusRepository", "Device Refocus scan failed", e)
        }
    }

    suspend fun saveRefocusPhoto(entity: RefocusPhotoEntity) = withContext(Dispatchers.IO) {
        dao.insertRefocusPhoto(entity)
    }

    suspend fun deleteRefocusPhoto(photoUri: String) = withContext(Dispatchers.IO) {
        val entity = dao.getRefocusPhoto(photoUri)
        if (entity != null) {
            try {
                File(entity.bundleDir).deleteRecursively()
            } catch (e: Exception) {
                // Ignore file deletion errors
            }
            dao.deleteRefocusPhoto(photoUri)
        }
    }

    fun getRefocusStorageDir(): File {
        val dir = File(context.filesDir, "refocus_bundles")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
