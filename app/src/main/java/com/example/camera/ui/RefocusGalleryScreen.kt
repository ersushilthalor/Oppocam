package com.example.camera.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.data.RefocusRepository
import com.example.camera.data.db.RefocusPhotoEntity
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Private in-app gallery for every Refocus capture tracked by the app.
 * Selecting a photo opens the existing tap-to-focus and focus-plane slider viewer.
 */
@Composable
fun RefocusGalleryScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember(context) { RefocusRepository(context) }
    val photos by repository.allRefocusPhotos.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(repository) { repository.discoverStoredRefocusPhotos() }
    var selectedPhoto by remember { mutableStateOf<RefocusPhotoEntity?>(null) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFF090B10)
    ) {
        if (selectedPhoto != null) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF10141D))
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedPhoto = null }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to Refocus gallery", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Refocus photo", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text("Tap anywhere to change focus", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                    }
                }
                InteractiveRefocusViewer(
                    refocusEntity = selectedPhoto!!,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF10141D))
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Close gallery", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Refocus Gallery", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                        Text("${photos.size} refocus ${if (photos.size == 1) "photo" else "photos"}", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                    }
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.padding(end = 12.dp).size(26.dp))
                }

                if (photos.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("No Refocus photos yet", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("Photos captured with Refocus Photo enabled will appear here. Open one to tap a subject or use the focus slider.", color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(photos, key = { it.photoUri }) { photo ->
                            RefocusGalleryRow(photo = photo, onClick = { selectedPhoto = photo })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefocusGalleryRow(
    photo: RefocusPhotoEntity,
    onClick: () -> Unit
) {
    val previewPath = remember(photo.bundleDir, photo.midPlanePath) {
        val mid = File(photo.bundleDir, "plane_${(photo.planeCount.coerceAtLeast(1) / 2)}.jpg")
        when {
            mid.exists() -> mid.absolutePath
            File(photo.midPlanePath).exists() -> photo.midPlanePath
            File(photo.nearPlanePath).exists() -> photo.nearPlanePath
            else -> null
        }
    }
    val thumbnail by produceState<android.graphics.Bitmap?>(initialValue = null, previewPath) {
        value = withContext(Dispatchers.IO) {
            previewPath?.let { path ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 320 || bounds.outHeight / (sample * 2) >= 320) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171B24))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(width = 112.dp, height = 84.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF252B36)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = "Refocus photo thumbnail",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(photo.timestamp)),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    "${photo.width.takeIf { it > 0 } ?: "—"} × ${photo.height.takeIf { it > 0 } ?: "—"}  ·  ${photo.planeCount.coerceAtLeast(3)} focus planes",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp
                )
                Text("Tap to adjust focus  ›", color = Color(0xFFFFD54F), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
