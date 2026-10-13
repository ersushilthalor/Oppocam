package com.example.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.pipeline.model.PipelinePreset
import com.example.camera.ui.components.FloatingSelectorDefaults
import com.example.camera.ui.components.FloatingWindowCard

/**
 * 6-blade camera shutter aperture icon matching the Hasselblad design in the reference screenshot.
 */
@Composable
fun CameraApertureShutterIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerR = size.minDimension * 0.44f
        val innerR = outerR * 0.38f

        // Outer smooth rim
        drawCircle(
            color = tint,
            radius = outerR,
            center = center,
            style = Stroke(width = 1.8.dp.toPx())
        )

        // 6 curved spiral blades forming the central hexagonal aperture
        for (i in 0 until 6) {
            val startRad = Math.toRadians((i * 60.0)).toFloat()
            val endRad = Math.toRadians((i * 60.0 + 82.0)).toFloat()

            val pStart = Offset(
                center.x + outerR * kotlin.math.cos(startRad),
                center.y + outerR * kotlin.math.sin(startRad)
            )
            val pEnd = Offset(
                center.x + innerR * kotlin.math.cos(endRad),
                center.y + innerR * kotlin.math.sin(endRad)
            )

            drawLine(
                color = tint,
                start = pStart,
                end = pEnd,
                strokeWidth = 1.6.dp.toPx()
            )
        }
    }
}

/**
 * Builds a pointy-topped hexagon path with smoothly rounded corners.
 */
fun buildRoundedHexagonPath(
    width: Float,
    height: Float,
    cornerRadius: Float
): Path {
    val points = listOf(
        Offset(width * 0.5f, 0f),              // 0: Top
        Offset(width, height * 0.25f),         // 1: Top-Right
        Offset(width, height * 0.75f),         // 2: Bottom-Right
        Offset(width * 0.5f, height),          // 3: Bottom
        Offset(0f, height * 0.75f),            // 4: Bottom-Left
        Offset(0f, height * 0.25f)             // 5: Top-Left
    )

    val path = Path()
    for (i in 0 until 6) {
        val prev = points[(i + 5) % 6]
        val curr = points[i]
        val next = points[(i + 1) % 6]

        val vPrev = Offset(prev.x - curr.x, prev.y - curr.y)
        val lenPrev = kotlin.math.hypot(vPrev.x, vPrev.y).coerceAtLeast(1f)
        val r1 = cornerRadius.coerceAtMost(lenPrev * 0.35f)
        val pStart = Offset(curr.x + vPrev.x * (r1 / lenPrev), curr.y + vPrev.y * (r1 / lenPrev))

        val vNext = Offset(next.x - curr.x, next.y - curr.y)
        val lenNext = kotlin.math.hypot(vNext.x, vNext.y).coerceAtLeast(1f)
        val r2 = cornerRadius.coerceAtMost(lenNext * 0.35f)
        val pEnd = Offset(curr.x + vNext.x * (r2 / lenNext), curr.y + vNext.y * (r2 / lenNext))

        if (i == 0) {
            path.moveTo(pStart.x, pStart.y)
        } else {
            path.lineTo(pStart.x, pStart.y)
        }
        path.quadraticTo(curr.x, curr.y, pEnd.x, pEnd.y)
    }
    path.close()
    return path
}

/**
 * Redesigned Custom Pipeline Floating Window in Photo Mode.
 *
 * Visual Characteristics matching reference design:
 * - Translucent dark glass panel with rounded corners and frosted appearance.
 * - Header: Gold bullet dot + PIPELINE (white) PRESETS (gold).
 * - No separate top-right selected-profile badge, no ON/OFF switch.
 * - Compact, glossy 3D hexagonal cards with layered edges, depth, soft shadows, and subtle lighting.
 * - Selected card has gold outline, gold glow, top-right checkmark, and gold title.
 * - Unselected cards have sleek dark glass background, subtle metallic rim, and accent dots.
 * - Horizontally scrollable row accommodating all built-in and custom presets.
 * - Concise, clear footer description.
 */
@Composable
fun PipelinePresetFloatingWindow(
    activePreset: PipelinePreset,
    allPresets: List<PipelinePreset>,
    onPresetSelected: (PipelinePreset) -> Unit,
    isPipelineEnabled: Boolean = false,
    onToggleEnabled: (Boolean) -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val accentGold = Color(0xFFFFC107)

    FloatingWindowCard(
        modifier = modifier
            .wrapContentWidth()
            .widthIn(min = 320.dp, max = 370.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("pipeline_preset_floating_window"),
        shape = RoundedCornerShape(26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Header Bar: "• PIPELINE PRESETS"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gold bullet dot
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentGold)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "PIPELINE",
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.0.sp
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "PRESETS",
                    color = accentGold,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.0.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Horizontally Scrollable Hexagonal Preset Cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                allPresets.forEach { preset ->
                    val isNaturalSensor = preset.id == "preset_natural"
                    // If pipeline is disabled, "Off (Sensor)" is active.
                    // If pipeline is enabled, the matching activePreset is selected.
                    val isSelected = if (!isPipelineEnabled) {
                        isNaturalSensor
                    } else {
                        preset.id == activePreset.id && !isNaturalSensor
                    }

                    val title = when (preset.id) {
                        "preset_natural" -> "Off"
                        "preset_hasselblad" -> "Hasselblad"
                        "preset_samsung" -> "Samsung"
                        "preset_pixel" -> "Pixel"
                        "preset_iphone" -> "iPhone"
                        else -> preset.name
                    }

                    val subtitle = when (preset.id) {
                        "preset_natural" -> "Sensor"
                        "preset_hasselblad" -> "Natural & Soft\nRoll-off"
                        "preset_samsung" -> "Punchy &\nDetails"
                        "preset_pixel" -> "Balanced HDR &\nShadows"
                        "preset_iphone" -> "Warm Tone &\nSmooth"
                        else -> preset.subtitle.ifBlank { "Custom\nProfile" }
                    }

                    val accentColor = when (preset.id) {
                        "preset_natural" -> Color(0xFF64B5F6)
                        "preset_hasselblad" -> accentGold
                        "preset_samsung" -> Color(0xFF64B5F6)
                        "preset_pixel" -> Color(0xFF81C784)
                        "preset_iphone" -> Color(0xFFFFB74D)
                        else -> Color(0xFFCE93D8)
                    }

                    HexagonPresetCard(
                        title = title,
                        subtitle = subtitle,
                        presetId = preset.id,
                        isSelected = isSelected,
                        accentColor = accentColor,
                        onClick = {
                            if (isNaturalSensor) {
                                onToggleEnabled(false)
                                onPresetSelected(preset)
                            } else {
                                onToggleEnabled(true)
                                onPresetSelected(preset)
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Footer info caption
            Text(
                text = "Selected pipeline processes uncompressed sensor data directly on capture.",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 14.5.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * Individual 3D Glossy Hexagonal Preset Card.
 */
@Composable
private fun HexagonPresetCard(
    title: String,
    subtitle: String,
    presetId: String,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val cardWidth = 98.dp
    val cardHeight = 126.dp
    val gold = Color(0xFFFFC107)

    Box(
        modifier = Modifier
            .size(width = cardWidth, height = cardHeight)
            .clickable(onClick = onClick)
            .testTag("pipeline_preset_$presetId"),
        contentAlignment = Alignment.Center
    ) {
        // Hexagon Canvas rendering background, glossy gradient, 3D bevels, borders, outer glow, and indicators
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cornerRadius = 11.dp.toPx()
            val hexPath = buildRoundedHexagonPath(w, h, cornerRadius)

            if (isSelected) {
                // 1. Subtle Outer Golden Glow
                drawPath(
                    path = hexPath,
                    color = gold.copy(alpha = 0.15f),
                    style = Stroke(width = 6.dp.toPx())
                )
                drawPath(
                    path = hexPath,
                    color = gold.copy(alpha = 0.35f),
                    style = Stroke(width = 3.5.dp.toPx())
                )

                // 2. Glossy Card Fill Gradient
                drawPath(
                    path = hexPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xEE27231A),
                            Color(0xF51A1813),
                            Color(0xF8141310)
                        )
                    )
                )

                // 3. Inner 3D Highlight & Shadow (layered depth)
                val innerPath = buildRoundedHexagonPath(w - 4f, h - 4f, cornerRadius - 2f)
                drawPath(
                    path = innerPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.14f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.35f)
                        )
                    ),
                    style = Stroke(width = 1.2.dp.toPx())
                )

                // 4. Brilliant Gold Outline
                drawPath(
                    path = hexPath,
                    color = gold,
                    style = Stroke(width = 1.8.dp.toPx())
                )
            } else {
                // 1. Unselected Card Fill Gradient
                drawPath(
                    path = hexPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xEE222631),
                            Color(0xF5181A22),
                            Color(0xF8101117)
                        )
                    )
                )

                // 2. Layered 3D Edge Bevel
                val innerPath = buildRoundedHexagonPath(w - 3f, h - 3f, cornerRadius - 1.5f)
                drawPath(
                    path = innerPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.40f)
                        )
                    ),
                    style = Stroke(width = 1.dp.toPx())
                )

                // 3. Sleek Metallic Outer Rim
                drawPath(
                    path = hexPath,
                    color = Color(0x388E9EAF),
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }

            // Top-left dot indicator
            val dotColor = if (isSelected) gold else accentColor
            drawCircle(
                color = dotColor,
                radius = 2.6.dp.toPx(),
                center = Offset(w * 0.22f, h * 0.21f)
            )

            // Top-right selection checkmark indicator badge
            if (isSelected) {
                val checkCenter = Offset(w * 0.78f, h * 0.21f)
                // Small dark badge backing
                drawCircle(
                    color = Color(0xCC18150D),
                    radius = 5.dp.toPx(),
                    center = checkCenter
                )
                // Checkmark strokes
                val checkPath = Path().apply {
                    moveTo(checkCenter.x - 3.2.dp.toPx(), checkCenter.y - 0.2.dp.toPx())
                    lineTo(checkCenter.x - 0.8.dp.toPx(), checkCenter.y + 2.2.dp.toPx())
                    lineTo(checkCenter.x + 3.2.dp.toPx(), checkCenter.y - 2.2.dp.toPx())
                }
                drawPath(
                    path = checkPath,
                    color = gold,
                    style = Stroke(width = 1.4.dp.toPx())
                )
            }
        }

        // Inner Card Content (Icon, Title, Subtitle)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Icon
            PresetIcon(
                presetId = presetId,
                isSelected = isSelected,
                accentColor = accentColor
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Title
            Text(
                text = title,
                color = if (isSelected) gold else Color.White,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Subtitle
            Text(
                text = subtitle,
                color = if (isSelected) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.55f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                lineHeight = 11.sp,
                maxLines = 2
            )
        }
    }
}

/**
 * Resolves the appropriate icon for each pipeline preset.
 */
@Composable
private fun PresetIcon(
    presetId: String,
    isSelected: Boolean,
    accentColor: Color
) {
    val gold = Color(0xFFFFC107)

    when (presetId) {
        "preset_natural" -> {
            Icon(
                imageVector = Icons.Outlined.Block,
                contentDescription = "Off",
                tint = if (isSelected) gold else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp)
            )
        }
        "preset_hasselblad" -> {
            CameraApertureShutterIcon(
                tint = if (isSelected) gold else accentColor,
                modifier = Modifier.size(24.dp)
            )
        }
        "preset_samsung" -> {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = "Samsung",
                tint = if (isSelected) gold else accentColor,
                modifier = Modifier.size(24.dp)
            )
        }
        "preset_pixel" -> {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = "Pixel",
                tint = if (isSelected) gold else accentColor,
                modifier = Modifier.size(24.dp)
            )
        }
        "preset_iphone" -> {
            Icon(
                imageVector = Icons.Outlined.WbSunny,
                contentDescription = "iPhone",
                tint = if (isSelected) gold else accentColor,
                modifier = Modifier.size(24.dp)
            )
        }
        else -> {
            Icon(
                imageVector = Icons.Outlined.Layers,
                contentDescription = "Custom",
                tint = if (isSelected) gold else accentColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
