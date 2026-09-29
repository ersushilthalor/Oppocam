package com.cinedepth.pro.ui.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object UltraDepthClient {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(300, TimeUnit.SECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    suspend fun requestDepth(
        context: Context,
        uri: Uri,
        serverBaseUrl: String,
        uploadMaxDimension: Int = 1152
    ): Bitmap = withContext(Dispatchers.IO) {
        val uploadBitmap = DepthBlurEngine.decodeScaledBitmap(context, uri, uploadMaxDimension)
        try {
            val jpegBytes = ByteArrayOutputStream().use { out ->
                uploadBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.toByteArray()
            }
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    "photo.jpg",
                    jpegBytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(serverBaseUrl.trim().trimEnd('/') + "/v1/depth")
                .post(multipartBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Ultra depth request failed: HTTP ${response.code}")
                }
                val bodyBytes = response.body?.bytes()
                    ?: throw IOException("Ultra depth response was empty")
                BitmapFactory.decodeByteArray(bodyBytes, 0, bodyBytes.size)
                    ?: throw IOException("Ultra depth response was not a valid bitmap")
            }
        } finally {
            uploadBitmap.recycle()
        }
    }
}
