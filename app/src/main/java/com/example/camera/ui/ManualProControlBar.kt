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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    isAeLocked: Boolean,
    isAfLocked: Boolean,
    onTabSelected: (ProControlTab) -> Unit,
    onExposureChange: (Int) -> Unit,
    onIsoChange: (Int?) -> Unit,
    onShutterChange: (Long?) -> Unit,
    onWbChange: (WhiteBalanceMode) -> Unit,
    onFocusModeChange: (FocusMode) -> Unit,
    onFocusDistanceChange: (Float) -> Unit,
    onColorProfileChange: (ColorProfile) -> Unit,
    onToggleAeLock: () -> Unit,
    onToggleAfLock: () -> Unit,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        FrostedGlassBox(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("manual_pro_bar"),
            shape = RoundedCornerShape(26.dp),
            elevation = 20.dp,
            baseAlpha = 0.82f,
            baseTint = Color(0xFF0F121C)
        ) {
            Column(
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
            // Lock Indicators & Top Toggles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PRO CONTROLS",
                    color = Color(0xFFFFD54F),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // AE Lock Chip
                    FilterChip(
                        selected = isAeLocked,
                        onClick = onToggleAeLock,
                        label = {
                            Text(
                                text = if (isAeLocked) "AE LOCKED" else "AE UNLOCKED",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isAeLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFD54F),
                            selectedLabelColor = Color.Black,
                            selectedLeadingIconColor = Color.Black,
                            containerColor = Color.White.copy(alpha = 0.08f),
                            labelColor = Color.White.copy(alpha = 0.8f),
                            iconColor = Color.White.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier.testTag("ae_lock_chip")
                    )

                    // AF Lock Chip
                    FilterChip(
                        selected = isAfLocked,
                        onClick = onToggleAfLock,
                        label = {
                            Text(
                                text = if (isAfLocked) "AF LOCKED" else "AF UNLOCKED",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isAfLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF64FFDA),
                            selectedLabelColor = Color.Black,
                            selectedLeadingIconColor = Color.Black,
                            containerColor = Color.White.copy(alpha = 0.08f),
                            labelColor = Color.White.copy(alpha = 0.8f),
                            iconColor = Color.White.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier.testTag("af_lock_chip")
                    )

                    // Dismiss Pro Controls Button
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("close_pro_controls_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Pro Controls",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Value Selector Slider / Preset Row according to Active Tab
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(60.dp),
                contentAlignment = Alignment.Center
            ) {
                when (activeTab) {
                    ProControlTab.EXPOSURE -> {
                        ExposureControlContent(
                            compensation = exposureCompensation,
                            minComp = capabilities.minExposureCompensation,
                            maxComp = capabilities.maxExposureCompensation,
                            step = capabilities.exposureCompensationStep,
                            onValueChange = onExposureChange
                        )
                    }
                    ProControlTab.ISO -> {
                        IsoControlContent(
                            currentIso = manualIso,
                            minIso = capabilities.minIso,
                            maxIso = capabilities.maxIso,
                            onIsoChange = onIsoChange
                        )
                    }
                    ProControlTab.SHUTTER -> {
                        ShutterControlContent(
                            currentNs = manualShutterSpeedNs,
                            onShutterChange = onShutterChange
                        )
                    }
                    ProControlTab.WB -> {
                        WbControlContent(
                            currentWb = whiteBalance,
                            supportedModes = capabilities.supportedAwbModes,
                            onWbChange = onWbChange
                        )
                    }
                    ProControlTab.FOCUS -> {
                        FocusControlContent(
                            currentMode = focusMode,
                            distance = manualFocusDistance,
                            minFocusDistance = capabilities.minFocusDistance,
                            onModeChange = onFocusModeChange,
                            onDistanceChange = onFocusDistanceChange
                        )
                    }
                    ProControlTab.TONE -> {
                        ToneControlContent(
                            currentProfile = colorProfile,
                            supportsTonemap = capabilities.supportsTonemapCurve,
                            onProfileChange = onColorProfileChange
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Horizontal Tab Selector Strip (EV, ISO, SEC, WB, FOCUS, TONE)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProControlTab.entries.forEach { tab ->
                    val isSelected = tab == activeTab
                    val valueLabel = when (tab) {
                        ProControlTab.EXPOSURE -> {
                            val evVal = exposureCompensation * capabilities.exposureCompensationStep
                            if (evVal >= 0) "+%.1f".format(evVal) else "%.1f".format(evVal)
                        }
                        ProControlTab.ISO -> manualIso?.toString() ?: "AUTO"
                        ProControlTab.SHUTTER -> formatShutterSpeed(manualShutterSpeedNs)
                        ProControlTab.WB -> whiteBalance.title.take(4).uppercase()
                        ProControlTab.FOCUS -> if (focusMode == FocusMode.MANUAL) "%.1f".format(manualFocusDistance) else focusMode.title
                        ProControlTab.TONE -> colorProfile.title.take(4).uppercase()
                    }

                    Column(
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) Color.White.copy(alpha = 0.18f) else Color.Transparent
                            )
                            .clickable { onTabSelected(tab) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = tab.label,
                            color = if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = valueLabel,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.4f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ExposureControlContent(
    compensation: Int,
    minComp: Int,
    maxComp: Int,
    step: Float,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val evValue = compensation * step
        Text(
            text = "EV: ${if (evValue >= 0) "+%.2f".format(evValue) else "%.2f".format(evValue)}",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Slider(
            value = compensation.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = minComp.toFloat()..maxComp.toFloat(),
            steps = maxOf(0, (maxComp - minComp) - 1),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFFD54F),
                activeTrackColor = Color(0xFFFFD54F),
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            ),
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun IsoControlContent(
    currentIso: Int?,
    minIso: Int,
    maxIso: Int,
    onIsoChange: (Int?) -> Unit
) {
    val isoValues = listOf(null, 50, 100, 200, 400, 800, 1600, 3200, 6400)
        .filter { it == null || (it in minIso..maxIso) }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(isoValues) { iso ->
            val isSelected = currentIso == iso
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

@Composable
private fun ShutterControlContent(
    currentNs: Long?,
    onShutterChange: (Long?) -> Unit
) {
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
            val isSelected = currentNs == ns
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

@Composable
private fun WbControlContent(
    currentWb: WhiteBalanceMode,
    supportedModes: List<WhiteBalanceMode>,
    onWbChange: (WhiteBalanceMode) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(supportedModes) { wb ->
            val isSelected = currentWb == wb
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

@Composable
private fun FocusControlContent(
    currentMode: FocusMode,
    distance: Float,
    minFocusDistance: Float,
    onModeChange: (FocusMode) -> Unit,
    onDistanceChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FocusMode.entries.forEach { mode ->
                val isSelected = currentMode == mode
                FilterChip(
                    selected = isSelected,
                    onClick = { onModeChange(mode) },
                    label = {
                        Text(
                            text = mode.title,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF64FFDA),
                        selectedLabelColor = Color.Black,
                        containerColor = Color.White.copy(alpha = 0.1f),
                        labelColor = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (currentMode == FocusMode.MANUAL) {
            Slider(
                value = distance,
                onValueChange = onDistanceChange,
                valueRange = 0f..maxOf(1f, minFocusDistance),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF64FFDA),
                    activeTrackColor = Color(0xFF64FFDA),
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                ),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun ToneControlContent(
    currentProfile: ColorProfile,
    supportsTonemap: Boolean,
    onProfileChange: (ColorProfile) -> Unit
) {
    val profiles = ColorProfile.entries.filter { profile ->
        if (profile.isFlat) supportsTonemap else true
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(profiles) { profile ->
            val isSelected = currentProfile == profile
            FilterChip(
                selected = isSelected,
                onClick = { onProfileChange(profile) },
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

private fun formatShutterSpeed(ns: Long?): String {
    if (ns == null) return "AUTO"
    val sec = ns.toDouble() / 1_000_000_000.0
    return if (sec >= 1.0) {
        "%.0fs".format(sec)
    } else {
        "1/%.0f".format(1.0 / sec)
    }
}
