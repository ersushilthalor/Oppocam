package com.example.camera.hdr.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.hdr.data.db.HdrVideoJobEntity
import com.example.camera.hdr.model.HdrJobStatus
import com.example.camera.hdr.model.HdrProcessingProgress
import com.example.camera.hdr.model.ProcessingPriority
import com.example.camera.hdr.queue.HdrVideoQueueManager
import com.example.camera.ui.components.FrostedGlassBox
import java.io.File
import java.util.Locale

/**
 * Dedicated HDR Processing Queue & Management Section for the Camera Gallery.
 * Displays live progress, technical metrics, and provides user processing controls.
 */
@Composable
fun HdrGallerySection(
    jobs: List<HdrVideoJobEntity>,
    queueManager: HdrVideoQueueManager,
    onOpenComparison: (HdrVideoJobEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val progressMap by queueManager.activeProgressMap.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("hdr_gallery_section")
    ) {
        // Section Header with HDR Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFFFD54F), Color(0xFFFF9800))
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "HDR 10-BIT",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Text(
                    text = "Computational Queue",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "${jobs.size} videos",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (jobs.isEmpty()) {
            FrostedGlassBox(
                shape = RoundedCornerShape(16.dp),
                baseAlpha = 0.45f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No HDR Videos Recorded Yet",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Videos shot in HDR mode will appear here with background exposure fusion.",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(jobs, key = { it.id }) { job ->
                    val liveProgress = progressMap[job.id]
                    HdrJobCard(
                        job = job,
                        liveProgress = liveProgress,
                        queueManager = queueManager,
                        onOpenComparison = { onOpenComparison(job) }
                    )
                }
            }
        }
    }
}

@Composable
fun HdrJobCard(
    job: HdrVideoJobEntity,
    liveProgress: HdrProcessingProgress?,
    queueManager: HdrVideoQueueManager,
    onOpenComparison: () -> Unit
) {
    val currentStatus = liveProgress?.status?.name ?: job.status
    val progress = liveProgress?.progressPercent ?: job.progressPercent
    val isComplete = currentStatus == HdrJobStatus.COMPLETE.name
    val isProcessing = currentStatus == HdrJobStatus.PROCESSING.name ||
            currentStatus == HdrJobStatus.HDR_MERGE.name ||
            currentStatus == HdrJobStatus.ENCODING.name
    val isPaused = currentStatus == HdrJobStatus.PAUSED.name

    FrostedGlassBox(
        shape = RoundedCornerShape(18.dp),
        elevation = 10.dp,
        baseAlpha = 0.65f,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hdr_job_card_${job.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Top Row: Title, Resolution Badge, Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${job.width}x${job.height} • ${job.fps} FPS • ${job.bitDepth}-bit HDR (ΔEV ${String.format(Locale.US, "%.1f", job.evDelta)})",
                        color = Color(0xFFFFD54F),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Status Badge
                StatusPill(status = currentStatus)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Progress Bar and Metrics
            if (!isComplete) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFFFFD54F),
                    trackColor = Color.White.copy(alpha = 0.15f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val remainingText = if (liveProgress != null && liveProgress.estimatedRemainingSec > 0) {
                        "${liveProgress.estimatedRemainingSec}s remaining"
                    } else "Queued"

                    val speedText = if (liveProgress != null && liveProgress.processingSpeedFps > 0f) {
                        String.format(Locale.US, "%.1f FPS", liveProgress.processingSpeedFps)
                    } else ""

                    Text(
                        text = "$progress% • $remainingText",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )

                    if (speedText.isNotEmpty()) {
                        Text(
                            text = speedText,
                            color = Color(0xFFFFD54F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Storage Details
            Spacer(modifier = Modifier.height(6.dp))
            val origMb = job.originalSizeBytes / (1024 * 1024)
            val finalMb = (if (isComplete) job.finalHdrSizeBytes else (liveProgress?.finalHdrSizeBytes ?: 0L)) / (1024 * 1024)
            Text(
                text = if (isComplete) "Final Size: ${finalMb} MB (Raw Spool Cleaned)" else "Source: ${origMb} MB spooling • Output: ${finalMb} MB",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Control Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isComplete) {
                    Button(
                        onClick = onOpenComparison,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("original_vs_hdr_button")
                    ) {
                        Icon(imageVector = Icons.Default.Compare, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Original vs HDR", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { queueManager.reprocessJob(job.id) },
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Reprocess", color = Color.White, fontSize = 12.sp)
                    }
                } else {
                    if (isProcessing) {
                        OutlinedButton(
                            onClick = { queueManager.pauseJob(job.id) },
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause", color = Color.White, fontSize = 12.sp)
                        }
                    } else if (isPaused) {
                        Button(
                            onClick = { queueManager.resumeJob(job.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F)),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { queueManager.processNow(job.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F)),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Process Now", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = { queueManager.cancelJob(job.id) },
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Cancel", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Priority Badge / Selector
                var showPriorityMenu by remember { mutableStateOf(false) }
                Box {
                    Text(
                        text = job.priority,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .clickable { showPriorityMenu = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )

                    DropdownMenu(
                        expanded = showPriorityMenu,
                        onDismissRequest = { showPriorityMenu = false }
                    ) {
                        ProcessingPriority.values().forEach { prio ->
                            DropdownMenuItem(
                                text = { Text(prio.name) },
                                onClick = {
                                    queueManager.setPriority(job.id, prio)
                                    showPriorityMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusPill(status: String) {
    val (color, text) = when (status) {
        HdrJobStatus.COMPLETE.name -> Pair(Color(0xFF4CAF50), "Complete")
        HdrJobStatus.PROCESSING.name, HdrJobStatus.HDR_MERGE.name -> Pair(Color(0xFFFFD54F), "Merging HDR")
        HdrJobStatus.ENCODING.name -> Pair(Color(0xFF29B6F6), "Encoding 10-bit")
        HdrJobStatus.QUEUED.name -> Pair(Color(0xFFFFA726), "Queued")
        HdrJobStatus.CAPTURING.name -> Pair(Color(0xFFE91E63), "Capturing")
        HdrJobStatus.PAUSED.name -> Pair(Color.Gray, "Paused")
        HdrJobStatus.CANCELLED.name -> Pair(Color(0xFFEF5350), "Cancelled")
        HdrJobStatus.FAILED.name -> Pair(Color.Red, "Failed")
        else -> Pair(Color.White.copy(alpha = 0.5f), status)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.2f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
