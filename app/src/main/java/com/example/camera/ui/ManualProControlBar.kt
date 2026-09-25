package com.example.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.*
import com.example.camera.ui.components.FrostedGlassBox
import com.example.camera.viewmodel.ProControlTab
import kotlin.math.roundToInt

enum class ProActiveSlider {
    NONE,
    EV,
    ISO,
    SHUTTER,
    WB,
    FOCUS,
    SATURATION,
    CONTRAST,
    HIGHLIGHTS,
    SHADOWS,
    SHARPNESS,
    NOISE_REDUCTION,
    TONE
}

@Composable
fun ManualProControlBar(
    isOpen: Boolean,
    activeTab: ProControlTab,
    capabilities: HardwareCapabilities,
    exposureCompensation: Int,
    manualIso: Int?,
    manualShutterSpeedNs: Long?,
    whiteBalance: WhiteBalanceMode,
    focusMode: FocusMode,
    manualFocusDistance: Float,
    colorProfile: ColorProfile,
    // Image Adjustments
    proSaturation: Float = 0f,
    proContrast: Float = 1.0f,
    proHighlights: Float = 0f,
    proShadows: Float = 0f,
    proSharpness: Float = 15f,
    proNoiseReduction: Float = 12f,
    onSliderVisibilityChange: (Boolean) -> Unit = {},
    onTabSelected: (ProControlTab) -> Unit,
    onExposureChange: (Int) -> Unit,
    onIsoChange: (Int?) -> Unit,
    onShutterChange: (Long?) -> Unit,
    onWbChange: (WhiteBalanceMode) -> Unit,
    onFocusModeChange: (FocusMode) -> Unit,
    onFocusDistanceChange: (Float) -> Unit,
    onColorProfileChange: (ColorProfile) -> Unit,
    onProSaturationChange: (Float) -> Unit = {},
    onProContrastChange: (Float) -> Unit = {},
    onProHighlightsChange: (Float) -> Unit = {},
    onProShadowsChange: (Float) -> Unit = {},
    onProSharpnessChange: (Float) -> Unit = {},
    onProNoiseReductionChange: (Float) -> Unit = {},
    onResetProAdjustments: () -> Unit = {},
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var activeSlider by remember { mutableStateOf(ProActiveSlider.NONE) }

    LaunchedEffect(activeSlider) {
        onSliderVisibilityChange(activeSlider != ProActiveSlider.NONE)
    }

    AnimatedVisibility(
        visible = isOpen,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp)
                .testTag("manual_pro_bar"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Functional Adjustment Slider Overlay with clear ✕ close button
            AnimatedVisibility(
                visible = activeSlider != ProActiveSlider.NONE,
                enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
            ) {
                FrostedGlassBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    elevation = 16.dp,
                    baseAlpha = 0.88f,
                    baseTint = Color(0xFF0F121C)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        // Header with Title, Value, and clear ✕ Close Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val (sliderTitle, sliderValue) = when (activeSlider) {
                                ProActiveSlider.EV -> {
                                    val evVal = exposureCompensation * capabilities.exposureCompensationStep
                                    "EXPOSURE COMPENSATION" to (if (evVal >= 0) "+%.2f EV".format(evVal) else "%.2f EV".format(evVal))
                                }
                                ProActiveSlider.ISO -> "ISO SENSITIVITY" to (manualIso?.toString() ?: "AUTO")
                                ProActiveSlider.SHUTTER -> "SHUTTER SPEED" to formatShutterSpeed(manualShutterSpeedNs)
                                ProActiveSlider.WB -> "WHITE BALANCE" to whiteBalance.title
                                ProActiveSlider.FOCUS -> "FOCUS" to (if (focusMode == FocusMode.MANUAL) "%.2f".format(manualFocusDistance) else focusMode.title)
                                ProActiveSlider.SATURATION -> "SATURATION" to (if (proSaturation >= 0) "+%.0f".format(proSaturation) else "%.0f".format(proSaturation))
                                ProActiveSlider.CONTRAST -> "CONTRAST" to "%.2fx".format(proContrast)
                                ProActiveSlider.HIGHLIGHTS -> "HIGHLIGHTS" to (if (proHighlights >= 0) "+%.0f".format(proHighlights) else "%.0f".format(proHighlights))
                                ProActiveSlider.SHADOWS -> "SHADOWS" to (if (proShadows >= 0) "+%.0f".format(proShadows) else "%.0f".format(proShadows))
                                ProActiveSlider.SHARPNESS -> "SHARPNESS" to "%.0f".format(proSharpness)
                                ProActiveSlider.NOISE_REDUCTION -> "NOISE REDUCTION" to "%.0f".format(proNoiseReduction)
                                ProActiveSlider.TONE -> "COLOR PROFILE" to colorProfile.title
                                ProActiveSlider.NONE -> "" to ""
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = sliderTitle,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    text = sliderValue,
                                    color = Color(0xFFFFD54F),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            // Clear ✕ Close button
                            IconButton(
                                onClick = { activeSlider = ProActiveSlider.NONE },
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .testTag("close_slider_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close adjustment slider",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        val isTuningActive = activeSlider in listOf(
                            ProActiveSlider.SATURATION, ProActiveSlider.CONTRAST, ProActiveSlider.HIGHLIGHTS,
                            ProActiveSlider.SHADOWS, ProActiveSlider.SHARPNESS, ProActiveSlider.NOISE_REDUCTION, ProActiveSlider.TONE
                        )

                        // Sub-selector for Tuning adjustments
                        if (isTuningActive) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White.copy(alpha = 0.08f),
                                    border = androidx.compose.foundation.BorderStroke(0.8.dp, Color.White.copy(alpha = 0.2f)),
                                    modifier = Modifier.clickable { onResetProAdjustments() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.RestartAlt,
                                            contentDescription = "Reset",
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = "RESET",
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                val tuneTabs = listOf(
                                    Triple("SAT", if (proSaturation >= 0) "+%.0f".format(proSaturation) else "%.0f".format(proSaturation), ProActiveSlider.SATURATION),
                                    Triple("CONTR", "%.2fx".format(proContrast), ProActiveSlider.CONTRAST),
                                    Triple("HIGH", if (proHighlights >= 0) "+%.0f".format(proHighlights) else "%.0f".format(proHighlights), ProActiveSlider.HIGHLIGHTS),
                                    Triple("SHAD", if (proShadows >= 0) "+%.0f".format(proShadows) else "%.0f".format(proShadows), ProActiveSlider.SHADOWS),
                                    Triple("SHARP", "%.0f".format(proSharpness), ProActiveSlider.SHARPNESS),
                                    Triple("NR", "%.0f".format(proNoiseReduction), ProActiveSlider.NOISE_REDUCTION),
                                    Triple("TONE", colorProfile.title.take(4).uppercase(), ProActiveSlider.TONE)
                                )

                                tuneTabs.forEach { (lbl, valStr, targetSlider) ->
                                    val isSelected = activeSlider == targetSlider
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.08f),
                                        border = androidx.compose.foundation.BorderStroke(
                                            0.8.dp,
                                            if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.15f)
                                        ),
                                        modifier = Modifier.clickable { activeSlider = targetSlider }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text(
                                                text = lbl,
                                                color = if (isSelected) Color.Black else Color.White,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = valStr,
                                                color = if (isSelected) Color.Black else Color(0xFFFFD54F),
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Functional Slider / Selector based on active slider
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when (activeSlider) {
                                ProActiveSlider.EV -> {
                                    Slider(
                                        value = exposureCompensation.toFloat(),
                                        onValueChange = { onExposureChange(it.roundToInt()) },
                                        valueRange = capabilities.minExposureCompensation.toFloat()..capabilities.maxExposureCompensation.toFloat(),
                                        steps = maxOf(0, (capabilities.maxExposureCompensation - capabilities.minExposureCompensation) - 1),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFFFFD54F),
                                            activeTrackColor = Color(0xFFFFD54F),
                                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                        ),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                                ProActiveSlider.ISO -> {
                                    val isoValues = listOf(null, 50, 100, 200, 400, 800, 1600, 3200, 6400)
                                        .filter { it == null || (it in capabilities.minIso..capabilities.maxIso) }
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        items(isoValues) { iso ->
                                            val isSelected = manualIso == iso
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { onIsoChange(iso) },
                                                label = {
                                                    Text(
                                                        text = iso?.toString() ?: "AUTO",
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = Color(0xFFFFD54F),
                                                    selectedLabelColor = Color.Black,
                                                    containerColor = Color.White.copy(alpha = 0.1f),
                                                    labelColor = Color.White
                                                )
                                            )
                                        }
                                    }
                                }
                                ProActiveSlider.SHUTTER -> {
                                    val shutterPresets: List<Pair<String, Long?>> = listOf(
                                        "AUTO" to null,
                                        "1/4000" to 250_000L,
                                        "1/2000" to 500_000L,
                                        "1/1000" to 1_000_000L,
                                        "1/500" to 2_000_000L,
                                        "1/250" to 4_000_000L,
                                        "1/125" to 8_000_000L,
                                        "1/60" to 16_666_666L,
                                        "1/30" to 33_333_333L,
                                        "1/15" to 66_666_666L,
                                        "1/8" to 125_000_000L,
                                        "1/4" to 250_000_000L,
                                        "1/2" to 500_000_000L,
                                        "1s" to 1_000_000_000L
                                    )
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        items(shutterPresets) { (label, ns) ->
                                            val isSelected = manualShutterSpeedNs == ns
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { onShutterChange(ns) },
                                                label = {
                                                    Text(
                                                        text = label,
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = Color(0xFFFFD54F),
                                                    selectedLabelColor = Color.Black,
                                                    containerColor = Color.White.copy(alpha = 0.1f),
                                                    labelColor = Color.White
                                                )
                                            )
                                        }
                                    }
                                }
                                ProActiveSlider.WB -> {
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        items(capabilities.supportedAwbModes) { wb ->
                                            val isSelected = whiteBalance == wb
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { onWbChange(wb) },
                                                label = {
                                                    Text(
                                                        text = wb.title,
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = Color(0xFFFFD54F),
                                                    selectedLabelColor = Color.Black,
                                                    containerColor = Color.White.copy(alpha = 0.1f),
                                                    labelColor = Color.White
                                                )
                                            )
                                        }
                                    }
                                }
                                ProActiveSlider.FOCUS -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FocusMode.entries.forEach { mode ->
                                            val isSelected = focusMode == mode
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { onFocusModeChange(mode) },
                                                label = {
                                                    Text(
                                                        text = mode.title,
                                                        fontSize = 10.5.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = Color(0xFF64FFDA),
                                                    selectedLabelColor = Color.Black,
                                                    containerColor = Color.White.copy(alpha = 0.1f),
                                                    labelColor = Color.White
                                                )
                                            )
                                        }
                                        if (focusMode == FocusMode.MANUAL) {
                                            Slider(
                                                value = manualFocusDistance,
                                                onValueChange = onFocusDistanceChange,
                                                valueRange = 0f..maxOf(1f, capabilities.minFocusDistance),
                                                colors = SliderDefaults.colors(
                                                    thumbColor = Color(0xFF64FFDA),
                                                    activeTrackColor = Color(0xFF64FFDA),
                                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                                ProActiveSlider.SATURATION -> {
                                    Slider(
                                        value = proSaturation,
                                        onValueChange = onProSaturationChange,
                                        valueRange = -100f..100f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFFFFD54F),
                                            activeTrackColor = Color(0xFFFFD54F),
                                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                        ),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                                ProActiveSlider.CONTRAST -> {
                                    Slider(
                                        value = proContrast,
                                        onValueChange = onProContrastChange,
                                        valueRange = 0.6f..1.4f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFFFFD54F),
                                            activeTrackColor = Color(0xFFFFD54F),
                                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                        ),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                                ProActiveSlider.HIGHLIGHTS -> {
                                    Slider(
                                        value = proHighlights,
                                        onValueChange = onProHighlightsChange,
                                        valueRange = -100f..100f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFFFFD54F),
                                            activeTrackColor = Color(0xFFFFD54F),
                                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                        ),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                                ProActiveSlider.SHADOWS -> {
                                    Slider(
                                        value = proShadows,
                                        onValueChange = onProShadowsChange,
                                        valueRange = -100f..100f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFFFFD54F),
                                            activeTrackColor = Color(0xFFFFD54F),
                                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                        ),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                                ProActiveSlider.SHARPNESS -> {
                                    Slider(
                                        value = proSharpness,
                                        onValueChange = onProSharpnessChange,
                                        valueRange = 0f..100f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFFFFD54F),
                                            activeTrackColor = Color(0xFFFFD54F),
                                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                        ),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                                ProActiveSlider.NOISE_REDUCTION -> {
                                    Slider(
                                        value = proNoiseReduction,
                                        onValueChange = onProNoiseReductionChange,
                                        valueRange = 0f..100f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFFFFD54F),
                                            activeTrackColor = Color(0xFFFFD54F),
                                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                        ),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                                ProActiveSlider.TONE -> {
                                    val profiles = ColorProfile.entries.filter { profile ->
                                        if (profile.isFlat) capabilities.supportsTonemapCurve else true
                                    }
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        items(profiles) { profile ->
                                            val isSelected = colorProfile == profile
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { onColorProfileChange(profile) },
                                                label = {
                                                    Text(
                                                        text = profile.title,
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = Color(0xFFFFD54F),
                                                    selectedLabelColor = Color.Black,
                                                    containerColor = Color.White.copy(alpha = 0.1f),
                                                    labelColor = Color.White
                                                )
                                            )
                                        }
                                    }
                                }
                                ProActiveSlider.NONE -> {}
                            }
                        }
                    }
                }
            }

            // 2. Compact Circular Controls Bar for Focus, WB, Shutter, ISO, EV, ADJ, Exit
            FrostedGlassBox(
                modifier = Modifier
                    .wrapContentWidth()
                    .padding(horizontal = 4.dp),
                shape = RoundedCornerShape(26.dp),
                elevation = 16.dp,
                baseAlpha = 0.88f,
                baseTint = Color(0xFF0C0F17)
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // FOCUS
                    CompactCircularControl(
                        title = if (focusMode == FocusMode.MANUAL) "MF" else "AF",
                        value = if (focusMode == FocusMode.MANUAL) "%.1f".format(manualFocusDistance) else "AUTO",
                        isSelected = activeSlider == ProActiveSlider.FOCUS,
                        isCustom = focusMode == FocusMode.MANUAL,
                        accentColor = Color(0xFF64FFDA),
                        onClick = {
                            activeSlider = if (activeSlider == ProActiveSlider.FOCUS) ProActiveSlider.NONE else ProActiveSlider.FOCUS
                            onTabSelected(ProControlTab.FOCUS)
                        },
                        tag = "pro_circle_focus"
                    )

                    // WB
                    CompactCircularControl(
                        title = "WB",
                        value = whiteBalance.title.take(4).uppercase(),
                        isSelected = activeSlider == ProActiveSlider.WB,
                        isCustom = whiteBalance != WhiteBalanceMode.AUTO,
                        onClick = {
                            activeSlider = if (activeSlider == ProActiveSlider.WB) ProActiveSlider.NONE else ProActiveSlider.WB
                            onTabSelected(ProControlTab.WB)
                        },
                        tag = "pro_circle_wb"
                    )

                    // SEC
                    CompactCircularControl(
                        title = "SEC",
                        value = formatShutterSpeed(manualShutterSpeedNs),
                        isSelected = activeSlider == ProActiveSlider.SHUTTER,
                        isCustom = manualShutterSpeedNs != null,
                        onClick = {
                            activeSlider = if (activeSlider == ProActiveSlider.SHUTTER) ProActiveSlider.NONE else ProActiveSlider.SHUTTER
                            onTabSelected(ProControlTab.SHUTTER)
                        },
                        tag = "pro_circle_shutter"
                    )

                    // ISO
                    CompactCircularControl(
                        title = "ISO",
                        value = manualIso?.toString() ?: "AUTO",
                        isSelected = activeSlider == ProActiveSlider.ISO,
                        isCustom = manualIso != null,
                        onClick = {
                            activeSlider = if (activeSlider == ProActiveSlider.ISO) ProActiveSlider.NONE else ProActiveSlider.ISO
                            onTabSelected(ProControlTab.ISO)
                        },
                        tag = "pro_circle_iso"
                    )

                    // EV
                    val evVal = exposureCompensation * capabilities.exposureCompensationStep
                    val evText = if (evVal >= 0) "+%.1f".format(evVal) else "%.1f".format(evVal)
                    CompactCircularControl(
                        title = "EV",
                        value = evText,
                        isSelected = activeSlider == ProActiveSlider.EV,
                        isCustom = exposureCompensation != 0,
                        onClick = {
                            activeSlider = if (activeSlider == ProActiveSlider.EV) ProActiveSlider.NONE else ProActiveSlider.EV
                            onTabSelected(ProControlTab.EXPOSURE)
                        },
                        tag = "pro_circle_ev"
                    )

                    // ADJ (Tuning)
                    val isAnyAdjModified = proSaturation != 0f || proContrast != 1.0f || proHighlights != 0f ||
                            proShadows != 0f || proSharpness != 15f || proNoiseReduction != 12f || colorProfile != ColorProfile.STANDARD
                    val isAdjActive = activeSlider in listOf(
                        ProActiveSlider.SATURATION, ProActiveSlider.CONTRAST, ProActiveSlider.HIGHLIGHTS,
                        ProActiveSlider.SHADOWS, ProActiveSlider.SHARPNESS, ProActiveSlider.NOISE_REDUCTION, ProActiveSlider.TONE
                    )
                    CompactCircularControl(
                        title = "ADJ",
                        value = if (isAnyAdjModified) "CUSTOM" else "STD",
                        isSelected = isAdjActive,
                        isCustom = isAnyAdjModified,
                        accentColor = Color(0xFFFFB300),
                        onClick = {
                            activeSlider = if (isAdjActive) ProActiveSlider.NONE else ProActiveSlider.SATURATION
                        },
                        tag = "pro_circle_adj"
                    )

                    // CLOSE PRO MODE BUTTON
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable {
                                activeSlider = ProActiveSlider.NONE
                                onClose()
                            }
                            .testTag("close_pro_controls_button")
                            .padding(horizontal = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color(0x33FFFFFF))
                                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Pro Mode",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "EXIT",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactCircularControl(
    title: String,
    value: String,
    isSelected: Boolean,
    isCustom: Boolean,
    accentColor: Color = Color(0xFFFFD54F),
    onClick: () -> Unit,
    tag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .testTag(tag)
            .padding(horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isSelected -> accentColor
                        isCustom -> accentColor.copy(alpha = 0.25f)
                        else -> Color(0x99181C26)
                    }
                )
                .border(
                    width = if (isSelected || isCustom) 1.5.dp else 1.dp,
                    color = when {
                        isSelected -> accentColor
                        isCustom -> accentColor.copy(alpha = 0.8f)
                        else -> Color.White.copy(alpha = 0.2f)
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                color = if (isSelected) Color.Black else if (isCustom) accentColor else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            color = if (isSelected || isCustom) accentColor else Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}

private fun formatShutterSpeed(ns: Long?): String {
    if (ns == null) return "AUTO"
    val sec = ns.toDouble() / 1_000_000_000.0
    return if (sec >= 1.0) {
        "%.0fs".format(sec)
    } else {
        "1/%.0f".format(1.0 / sec)
    }
}
