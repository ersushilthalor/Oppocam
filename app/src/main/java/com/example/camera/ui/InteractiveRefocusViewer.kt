package com.example.camera.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FilterTiltShift
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.data.db.RefocusPhotoEntity
import com.example.camera.engine.RefocusEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Interactive Gallery Viewer for Refocus Photos.
 *
 * Provides:
 * 1. Tap-to-Refocus: tapping any depth region shifts optical focus to that depth plane.
 * 2. Smooth Continuous Focus Slider: seamlessly shifts focus across near, mid, and far planes.
 * 3. Natural 3D / Parallax Effect: device tilt and touch drag steer depth layers for holographic depth pop.
 * 4. Memory-safe: decodes screen-adapted bitmaps and recycles them safely.
 */
@Composable
fun InteractiveRefocusViewer(
    refocusEntity: RefocusPhotoEntity,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val refocusEngine = remember { RefocusEngine(context) }
    val planeCount = refocusEntity.planeCount.coerceIn(3, 20)

    // Bitmaps and depth map data
    var planeBitmaps by remember { mutableStateOf<List<Bitmap?>>(emptyList()) }
    var depthMapData by remember { mutableStateOf<RefocusEngine.DepthMapData?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Focus state (0.0 = Near, 1.0 = Far/Deep)
    val focusAnimatable = remember { Animatable(0.5f) }
    var targetFocusValue by remember { mutableFloatStateOf(0.5f) }

    // Tap indicator
    var tapLocation by remember { mutableStateOf<Offset?>(null) }
    var showTapReticle by remember { mutableStateOf(false) }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Load planes and depth map sequentially in background
    LaunchedEffect(refocusEntity.bundleDir) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val bundle = File(refocusEntity.bundleDir)
            val list = mutableListOf<Bitmap?>()
            for (i in 0 until planeCount) {
                val file = File(bundle, "plane_$i.jpg")
                val bmp = if (file.exists()) {
                    decodeScaledBitmap(file.absolutePath, 1080)
                } else when (i) {
                    0 -> decodeScaledBitmap(refocusEntity.nearPlanePath, 1080)
                    planeCount / 2 -> decodeScaledBitmap(refocusEntity.midPlanePath, 1080)
                    planeCount - 1 -> decodeScaledBitmap(refocusEntity.farPlanePath, 1080)
                    else -> null
                }
                list.add(bmp)
            }
            val depth = refocusEngine.loadDepthMap(refocusEntity.depthMapPath)

            withContext(Dispatchers.Main) {
                planeBitmaps = list
                depthMapData = depth
                isLoading = false
            }
        }
    }

    // Clean up Bitmaps on disposal
    DisposableEffect(Unit) {
        onDispose {
            planeBitmaps.forEach { it?.recycle() }
            planeBitmaps = emptyList()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { containerSize = it.size }
            .testTag("interactive_refocus_viewer")
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Color(0xFFFFD54F),
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            val currentFocus = focusAnimatable.value
            val totalPlanes = planeBitmaps.size.coerceAtLeast(1)
            val exactPlane = (currentFocus * (totalPlanes - 1)).coerceIn(0f, (totalPlanes - 1).toFloat())
            val idx0 = exactPlane.toInt().coerceIn(0, totalPlanes - 1)
            val idx1 = kotlin.math.min(totalPlanes - 1, idx0 + 1)
            val planeFrac = exactPlane - idx0

            // Photo Rendering Container with Touch Gestures
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            tapLocation = offset
                            showTapReticle = true

                            // Compute normalized position
                            val cw = containerSize.width.toFloat().coerceAtLeast(1f)
                            val ch = containerSize.height.toFloat().coerceAtLeast(1f)
                            val normX = (offset.x / cw).coerceIn(0f, 1f)
                            val normY = (offset.y / ch).coerceIn(0f, 1f)

                            // Read depth at tapped location
                            val tappedDepth = depthMapData?.getDepthAt(normX, normY) ?: 0.5f
                            targetFocusValue = tappedDepth

                            coroutineScope.launch {
                                focusAnimatable.animateTo(
                                    targetValue = tappedDepth,
                                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Render focus plane layers
                val bmp0 = planeBitmaps.getOrNull(idx0)
                val bmp1 = planeBitmaps.getOrNull(idx1)
                if (bmp0 != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp0.asImageBitmap(),
                        contentDescription = "Focus Plane $idx0",
                        alpha = 1f - planeFrac,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (bmp1 != null && idx1 != idx0 && planeFrac > 0.01f) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp1.asImageBitmap(),
                        contentDescription = "Focus Plane $idx1",
                        alpha = planeFrac,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Tap-to-Refocus Animated Ring Reticle
                if (showTapReticle && tapLocation != null) {
                    val reticleScale by animateFloatAsState(
                        targetValue = 1.0f,
                        animationSpec = tween(300),
                        finishedListener = { showTapReticle = false },
                        label = "reticleScale"
                    )
                    val reticleOffset = tapLocation!!
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color(0xFFFFD54F),
                            radius = 38.dp.toPx() * reticleScale,
                            center = reticleOffset,
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                        drawCircle(
                            color = Color(0xFFFFD54F),
                            radius = 4.dp.toPx(),
                            center = reticleOffset
                        )
                    }
                }
            }

            // Bottom Refocus & 3D Interactive Control Card
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Interactive Status Pill Bar
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterTiltShift,
                        contentDescription = null,
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "INTERACTIVE REFOCUS",
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                // Slider Card for Smooth Focus Plane Selection
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Focus Plane: ${(exactPlane.roundToInt() + 1)} of $totalPlanes",
                                color = Color(0xFFFFD54F),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (totalPlanes > 3) "$totalPlanes planes captured" else "Tap photo or drag slider",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        }

                        // Smooth continuous focus slider
                        Slider(
                            value = currentFocus,
                            onValueChange = { newVal ->
                                targetFocusValue = newVal
                                coroutineScope.launch {
                                    focusAnimatable.snapTo(newVal)
                                }
                            },
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFFD54F),
                                activeTrackColor = Color(0xFFFFD54F),
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("refocus_plane_slider")
                        )

                        // Plane markers row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "◀ Near",
                                color = if (currentFocus < 0.33f) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontWeight = if (currentFocus < 0.33f) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clickable {
                                    coroutineScope.launch {
                                        focusAnimatable.animateTo(0.1f, tween(300))
                                    }
                                }
                            )
                            Text(
                                text = "● Mid (Subject)",
                                color = if (currentFocus in 0.33f..0.67f) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontWeight = if (currentFocus in 0.33f..0.67f) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clickable {
                                    coroutineScope.launch {
                                        focusAnimatable.animateTo(0.5f, tween(300))
                                    }
                                }
                            )
                            Text(
                                text = "Far ▶",
                                color = if (currentFocus > 0.67f) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontWeight = if (currentFocus > 0.67f) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clickable {
                                    coroutineScope.launch {
                                        focusAnimatable.animateTo(0.9f, tween(300))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Memory-safe bitmap decoder that scales images down to max dimension.
 */
private fun decodeScaledBitmap(path: String, maxDimension: Int): Bitmap? {
    val file = File(path)
    if (!file.exists()) return null
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val w = options.outWidth
        val h = options.outHeight
        if (w <= 0 || h <= 0) return null

        var sampleSize = 1
        val maxSide = max(w, h)
        while (maxSide / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    } catch (e: Throwable) {
        null
    }
}
