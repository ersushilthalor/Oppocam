package com.example.camera.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.camera.model.CapturedMedia

import androidx.compose.foundation.border
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import com.example.camera.data.RefocusRepository
import com.example.camera.data.db.RefocusPhotoEntity
import com.example.camera.ui.components.FrostedGlassBox

import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import com.example.camera.ultrafast.data.UltraFastBurstRepository
import com.example.camera.ultrafast.model.UltraFastBurstEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerDialog(
    media: CapturedMedia?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (media == null) return
    val context = LocalContext.current
    var refocusEntity by remember(media.uri) { mutableStateOf<RefocusPhotoEntity?>(null) }

    // Fast Shutter Burst Group State
    val burstRepo = remember { UltraFastBurstRepository(context) }
    var burstEntity by remember(media.uri) { mutableStateOf<UltraFastBurstEntity?>(null) }
    var selectedBurstFrameIndex by remember(media.uri) { mutableIntStateOf(0) }
    var isBurstPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(media.uri) {
        if (!media.isVideo) {
            val repo = RefocusRepository(context)
            var entity = repo.getRefocusPhoto(media.uri.toString())
            var retries = 0
            while (entity == null && retries < 4) {
                kotlinx.coroutines.delay(200)
                entity = repo.getRefocusPhoto(media.uri.toString())
                retries++
            }
            refocusEntity = entity

            // Check if part of an Ultra Fast Burst
            var bEntity = burstRepo.getBurstForUri(media.uri.toString())
            if (bEntity == null && media.displayName.contains("BURST", ignoreCase = true)) {
                bEntity = burstRepo.getLatestBurst()
            }
            if (bEntity != null) {
                burstEntity = bEntity
                val uris = bEntity.getPhotoUris()
                val idx = uris.indexOf(media.uri.toString())
                selectedBurstFrameIndex = if (idx >= 0) idx else 0
            }
        }
    }

    // Burst automated playback loop
    LaunchedEffect(isBurstPlaying, burstEntity) {
        val entity = burstEntity ?: return@LaunchedEffect
        val uris = entity.getPhotoUris()
        if (uris.size <= 1) return@LaunchedEffect
        val interval = (1000L / entity.fps.coerceIn(5, 20)).coerceAtLeast(40L)
        while (isBurstPlaying) {
            kotlinx.coroutines.delay(interval)
            selectedBurstFrameIndex = (selectedBurstFrameIndex + 1) % uris.size
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("media_viewer_dialog")
        ) {
            // Media Preview, Interactive Refocus Viewer, or In-App Video Playback
            if (media.isVideo) {
                val videoAspectRatio = remember(media.uri) {
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(context, media.uri)
                        val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        val rawW = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                        val rawH = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                        retriever.release()
                        val isRotated = (rotation == 90 || rotation == 270)
                        val dispW = if (isRotated) rawH else rawW
                        val dispH = if (isRotated) rawW else rawH
                        (dispW.toFloat() / dispH.toFloat()).coerceIn(0.2f, 5.0f)
                    } catch (e: Exception) {
                        9f / 16f
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                setVideoURI(media.uri)
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(videoAspectRatio, matchHeightConstraintsFirst = true)
                    )
                }
            } else if (refocusEntity != null) {
                InteractiveRefocusViewer(
                    refocusEntity = refocusEntity!!,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val currentBurstUris = burstEntity?.getPhotoUris() ?: emptyList()
                val displayUri = if (burstEntity != null && currentBurstUris.isNotEmpty()) {
                    val frameUriStr = currentBurstUris.getOrNull(selectedBurstFrameIndex) ?: media.uri.toString()
                    android.net.Uri.parse(frameUriStr)
                } else {
                    media.uri
                }

                AsyncImage(
                    model = displayUri,
                    contentDescription = media.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top frosted bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                FrostedGlassBox(
                    shape = RoundedCornerShape(16.dp),
                    elevation = 12.dp,
                    baseAlpha = 0.70f,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (refocusEntity != null) {
                            Text(
                                text = "◎",
                                color = Color(0xFFFFD54F),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else if (burstEntity != null && burstEntity!!.frameCount > 1) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFFFB300))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.BurstMode,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "BURST · ${burstEntity!!.frameCount}",
                                        color = Color.Black,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        } else if (media.isVideo) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFFFD54F))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "VIDEO",
                                    color = Color.Black,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                        Text(
                            text = if (refocusEntity != null) "Refocus · ${media.displayName}" else if (burstEntity != null && burstEntity!!.frameCount > 1) "Burst (${burstEntity!!.frameCount} shots)" else media.displayName,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Share button
                    IconButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = if (media.isVideo) "video/*" else "image/*"
                                putExtra(Intent.EXTRA_STREAM, media.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Media"))
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color.White
                        )
                    }
                }
            }

            // Bottom bar for Stock-Camera style Burst Group browsing & playback
            val activeBurst = burstEntity
            if (activeBurst != null && activeBurst.frameCount > 1) {
                val burstUris = activeBurst.getPhotoUris()
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 28.dp)
                ) {
                    FrostedGlassBox(
                        shape = RoundedCornerShape(28.dp),
                        elevation = 16.dp,
                        baseAlpha = 0.85f,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "Frame ${selectedBurstFrameIndex + 1} of ${activeBurst.frameCount} (${activeBurst.fps} FPS)",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Previous frame
                                IconButton(
                                    onClick = {
                                        isBurstPlaying = false
                                        selectedBurstFrameIndex = if (selectedBurstFrameIndex > 0) selectedBurstFrameIndex - 1 else burstUris.lastIndex
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Previous Frame",
                                        tint = Color.White
                                    )
                                }

                                // Play / Pause burst sequence playback
                                IconButton(
                                    onClick = { isBurstPlaying = !isBurstPlaying },
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFFB300))
                                ) {
                                    Icon(
                                        imageVector = if (isBurstPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isBurstPlaying) "Pause Burst" else "Play Burst",
                                        tint = Color.Black
                                    )
                                }

                                // Next frame
                                IconButton(
                                    onClick = {
                                        isBurstPlaying = false
                                        selectedBurstFrameIndex = (selectedBurstFrameIndex + 1) % burstUris.size
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Next Frame",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
