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

@Composable
fun MediaViewerDialog(
    media: CapturedMedia?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (media == null) return
    val context = LocalContext.current
    var refocusEntity by remember(media.uri) { mutableStateOf<RefocusPhotoEntity?>(null) }

    LaunchedEffect(media.uri) {
        if (!media.isVideo) {
            val repo = RefocusRepository(context)
            var entity = repo.getRefocusPhoto(media.uri.toString())
            var retries = 0
            while (entity == null && retries < 8) {
                kotlinx.coroutines.delay(250)
                entity = repo.getRefocusPhoto(media.uri.toString())
                retries++
            }
            refocusEntity = entity
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
                AsyncImage(
                    model = media.uri,
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
                        }
                        Text(
                            text = if (refocusEntity != null) "Refocus · ${media.displayName}" else media.displayName,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

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
    }
}
