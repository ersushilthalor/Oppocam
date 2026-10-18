package com.example.camera.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    onOpenDeviceGallery: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember(context) { RefocusRepository(context) }
    val photos by repository.allRefocusPhotos.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(repository) {
        repository.discoverStoredRefocusPhotos()
        repository.discoverDeviceRefocusPhotos()
    }
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
                    TextButton(onClick = onOpenDeviceGallery) {
                        Text("Device gallery", color = Color(0xFFFFD54F), fontSize = 12.sp)
                    }
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
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(photos, key = { it.photoUri }) { photo ->
                            RefocusGalleryTile(photo = photo, onClick = { selectedPhoto = photo })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefocusGalleryTile(
    photo: RefocusPhotoEntity,
    onClick: () -> Unit
) {
    val previewPath = remember(photo.bundleDir, photo.midPlanePath, photo.planeCount) {
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
                while (bounds.outWidth / (sample * 2) >= 240 || bounds.outHeight / (sample * 2) >= 240) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF20242D))
            .clickable(onClick = onClick)
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!.asImageBitmap(),
                contentDescription = "Refocus photo, ${photo.planeCount} frames",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.CenterFocusStrong,
                    contentDescription = null,
                    tint = Color(0xFFFFD54F),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Small, subtle frame-count tag directly over the photo.
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(3.dp),
            color = Color.Black.copy(alpha = 0.48f),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                "${photo.planeCount.coerceAtLeast(3)}F",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
