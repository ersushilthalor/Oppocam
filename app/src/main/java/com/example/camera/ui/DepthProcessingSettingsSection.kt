package com.example.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinedepth.pro.ui.BokehPreset
import com.cinedepth.pro.ui.LensEffect
import com.cinedepth.pro.ui.LensProfile
import com.cinedepth.pro.ui.blur.DepthBlurEngine
import com.example.camera.model.BokehStyle
import com.example.camera.model.PortraitConfig

/**
 * CineDepth Pro Engine Settings Page inside Camera Settings.
 *
 * Provides:
 * - Real-time CineDepth Pro dual-pass AGSL shader pipeline status
 * - Neural monocular depth estimation & ML Kit high-resolution hair contour matting
 * - Optical lens effects (Classic, Noctilux Creamy, Helios Swirl, Bloom, Star, Hexagon, CinemaScope Anamorphic)
 * - Fine-grain edge refinement, highlight bloom, and vignetting controls
 */
@Composable
fun DepthProcessingSettingsPage(
    portraitConfig: PortraitConfig,
    onPortraitConfigChange: (PortraitConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val estimator = remember { DepthBlurEngine.getEstimator(context) }
    val isModelReady = remember { estimator.isReady() }

    val accentGold = Color(0xFFFFD54F)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("depth_processing_settings_page"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Card: CineDepth Pro Engine Status
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF141926),
                border = BorderStroke(1.dp, accentGold.copy(alpha = 0.40f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x2E4CAF50)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF66BB6A),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "CineDepth Pro Engine",
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "Computational Optical Portrait Suite",
                                    color = accentGold,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x334CAF50),
                            border = BorderStroke(1.dp, Color(0xFF66BB6A))
                        ) {
                            Text(
                                text = "ACTIVE",
                                color = Color(0xFF81C784),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Real-time dual-pass AGSL RuntimeShader pipeline with neural monocular depth and high-resolution raw size silhouette & hair matting. Captures produce genuine optical bokeh with golden angle disc sampling and anamorphic flare streaks.",
                        color = Color.White.copy(alpha = 0.80f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        // Section: Architecture Stages
        item {
            Text(
                text = "PIPELINE ARCHITECTURE",
                color = accentGold,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val stages = listOf(
                    Triple(
                        "AI Monocular Depth Estimation",
                        "Depth-Anything-V2 ViT-S neural backbone with hardware GPU Delegate acceleration.",
                        if (isModelReady) "Neural TFLite GPU" else "Adaptive Fallback"
                    ),
                    Triple(
                        "Raw-Size Portrait Silhouette Matting",
                        "ML Kit high-resolution segmentation mask preserving fine hair strands and boundaries.",
                        "ML Kit HW-Accelerated"
                    ),
                    Triple(
                        "Pass 1: Luma-Weighted Edge Refinement",
                        "AGSL RuntimeShader bilateral filter snapping depth discontinuities to color luminance edges.",
                        "AGSL GPU Runtime"
                    ),
                    Triple(
                        "Pass 2: Optical Bokeh Accumulation",
                        "Golden-angle disc gathering (up to 72 samples), cat's-eye vignetting, and anamorphic flare streaks.",
                        "AGSL Multi-Strata"
                    )
                )

                stages.forEachIndexed { idx, (title, desc, badge) ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF10131E),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(accentGold.copy(alpha = 0.15f))
                                    .border(1.dp, accentGold.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    color = accentGold,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = title,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = badge,
                                        color = Color(0xFF81C784),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = desc,
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.5.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section: Optical Bokeh Characters & Lens Profiles
        item {
            Text(
                text = "CINEDEPTH PRO LENS PROFILES",
                color = accentGold,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LensProfile.entries.forEach { profile ->
                    val isSelected = when (profile) {
                        LensProfile.Noctilux -> portraitConfig.bokehStyle == BokehStyle.LEICA_3D_POP
                        LensProfile.GMaster -> portraitConfig.bokehStyle == BokehStyle.NATURAL_ROUND
                        LensProfile.Helios -> portraitConfig.bokehStyle == BokehStyle.ZEISS_SWIRL
                        LensProfile.CinemaScope -> portraitConfig.bokehStyle == BokehStyle.SOFT_ELLIPTICAL
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) Color(0xFF192033) else Color(0xFF10131E),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) accentGold else Color.White.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val targetStyle = when (profile) {
                                    LensProfile.Noctilux -> BokehStyle.LEICA_3D_POP
                                    LensProfile.GMaster -> BokehStyle.NATURAL_ROUND
                                    LensProfile.Helios -> BokehStyle.ZEISS_SWIRL
                                    LensProfile.CinemaScope -> BokehStyle.SOFT_ELLIPTICAL
                                }
                                onPortraitConfigChange(
                                    portraitConfig.copy(
                                        bokehStyle = targetStyle,
                                        simulatedAperture = when (profile) {
                                            LensProfile.Noctilux -> "f/0.95"
                                            LensProfile.GMaster -> "f/1.4"
                                            LensProfile.Helios -> "f/1.5"
                                            LensProfile.CinemaScope -> "f/1.2"
                                        },
                                        blurStrength = profile.params.blurStrength * 100f
                                    )
                                )
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.label,
                                    color = Color.White,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                val effectDesc = when (profile.params.lensEffect) {
                                    LensEffect.Creamy -> "Liquid 3D pop • Soft spherical transition • Specular bloom"
                                    LensEffect.Classic -> "Natural circular optical aperture • Golden angle blur"
                                    LensEffect.Bubble -> "Helios 44-2 swirly edge cat's-eye bokeh & vignette"
                                    LensEffect.Anamorphic -> "1.65x oval bokeh disc stretch with streak flares"
                                    else -> "Custom CineDepth Pro optical bokeh"
                                }
                                Text(
                                    text = effectDesc,
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.sp
                                )
                            }

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = accentGold,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section: Live Viewfinder Preview Toggles
        item {
            Text(
                text = "VIEWFINDER & CAPTURE CONTROLS",
                color = accentGold,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF10131E),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Live CineDepth Viewfinder Preview",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Render real-time CineDepth AGSL bokeh in the camera preview",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = portraitConfig.liveAperturePreviewEnabled,
                            onCheckedChange = { enabled ->
                                onPortraitConfigChange(portraitConfig.copy(liveAperturePreviewEnabled = enabled))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = accentGold,
                                uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show 3D Depth Map Overlay",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Display neural depth colormap overlay on the viewfinder",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = portraitConfig.showDepthPreview,
                            onCheckedChange = { enabled ->
                                onPortraitConfigChange(portraitConfig.copy(showDepthPreview = enabled))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = accentGold,
                                uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                            )
                        )
                    }
                }
            }
        }
    }
}
