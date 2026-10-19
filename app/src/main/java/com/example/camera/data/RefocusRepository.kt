package com.example.camera.data

import android.content.Context
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
