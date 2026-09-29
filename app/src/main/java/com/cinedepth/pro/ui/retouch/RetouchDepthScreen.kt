package com.cinedepth.pro.ui.retouch

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinedepth.pro.ui.BokehPreset
import com.cinedepth.pro.ui.LensEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetouchDepthScreen(
    viewModel: RetouchDepthViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val accentGold = Color(0xFFFFD54F)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "CineDepth Pro Retouch",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        uiState.statusMessage?.let {
                            Text(
                                text = it,
                                fontSize = 11.sp,
                                color = accentGold
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.toggleDepthMapOverlay() },
                        modifier = Modifier.testTag("toggle_depth_overlay")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Layers,
                            contentDescription = "Depth Map",
                            tint = if (uiState.showDepthMapOverlay) accentGold else Color.White
                        )
                    }
                    IconButton(
                        onClick = { viewModel.exportPortrait() },
                        enabled = !uiState.isExporting && uiState.renderedBitmap != null,
                        modifier = Modifier.testTag("export_cinedepth_portrait")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SaveAlt,
                            contentDescription = "Save to Gallery",
                            tint = if (uiState.isExporting) Color.Gray else accentGold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F121C))
            )
        },
        containerColor = Color(0xFF0B0D14),
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Interactive Image Canvas with tap-to-focus
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val displayBitmap = if (uiState.showDepthMapOverlay) {
                    uiState.depthBitmap
                } else {
                    uiState.renderedBitmap ?: uiState.sourceBitmap
                }

                if (displayBitmap != null) {
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = "Portrait Image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val normX = (offset.x / size.width).coerceIn(0f, 1f)
                                    val normY = (offset.y / size.height).coerceIn(0f, 1f)
                                    viewModel.onFocusPointTapped(Offset(normX, normY))
                                }
                            }
                    )
                }

                if (uiState.isLoading || uiState.isExporting) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.50f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = accentGold)
                    }
                }
            }

            // Bottom Controls Bar
            Surface(
                color = Color(0xFF141926),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Lens Effect Selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BokehPreset.entries.forEach { preset ->
                            val isSelected = uiState.params.lensEffect == preset.effect
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) accentGold.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.06f))
                                    .border(
                                        1.dp,
                                        if (isSelected) accentGold else Color.White.copy(alpha = 0.12f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { viewModel.setLensEffect(preset.effect) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = preset.label,
                                    color = if (isSelected) accentGold else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Blur Strength Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Aperture Blur",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${(uiState.params.blurStrength * 100).toInt()}%",
                            color = accentGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = uiState.params.blurStrength,
                        onValueChange = { viewModel.setBlurStrength(it) },
                        valueRange = 0.02f..1.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = accentGold,
                            activeTrackColor = accentGold,
                            inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
