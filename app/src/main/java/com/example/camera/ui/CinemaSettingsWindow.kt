package com.example.camera.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.data.CustomLutItem
import com.example.camera.data.CustomLutRepository
import com.example.camera.model.*
import com.example.camera.ui.components.FrostedGlassBox

/**
 * Modern, minimal, and premium Cinema Settings Window.
 * Features:
 * - Authentic Liquid Glass / Frosted Glass physical design with subtle specular highlights
 * - Unified single LUT pipeline: LUT selection automatically applies to live viewfinder & recorded video
 * - Hollywood-inspired cinematic LUT gallery + custom .cube LUT file importer
 * - Persisted controls (Live Noise Reduction OFF stays OFF across launches)
 * - Streamlined, uncluttered cinematography controls
 */
@Composable
fun CinemaSettingsWindow(
    config: CinemaConfig,
    capabilities: CinemaHardwareCapabilities,
    rec2020AutoToneParams: com.example.camera.engine.Rec2020AutoToneParams? = null,
    onConfigChange: (CinemaConfig) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val customLutRepo = remember(context) { CustomLutRepository(context) }
    val customLuts by customLutRepo.customLuts.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            var resolvedName: String? = null
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && cursor.moveToFirst()) {
                        resolvedName = cursor.getString(nameIdx)
                    }
                }
            } catch (ignored: Exception) {}

            val fileName = (resolvedName ?: uri.lastPathSegment?.substringAfterLast('/'))
                ?.removeSuffix(".cube")
                ?.takeIf { it.isNotBlank() } ?: "Custom Grade"

            val imported = customLutRepo.importLut(uri, fileName)
            if (imported != null) {
                Toast.makeText(context, "Imported LUT: ${imported.title}", Toast.LENGTH_SHORT).show()
                onConfigChange(
                    config.copy(
                        selectedLut = CinematicLut.CUSTOM,
                        customLutPath = imported.filePath,
                        customLutName = imported.title,
                        isBakeLutToOutput = true,
                        isLutPreviewEnabled = true
                    )
                )
            } else {
                Toast.makeText(context, "Invalid or unsupported .cube file format", Toast.LENGTH_LONG).show()
            }
        }
    }

    FrostedGlassBox(
        modifier = modifier
            .wrapContentWidth()
            .widthIn(min = 270.dp, max = 340.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("cinema_settings_window"),
        shape = RoundedCornerShape(20.dp),
        elevation = 14.dp,
        baseAlpha = 0.82f,
        baseTint = Color(0xFF0F121C)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .heightIn(max = 330.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header: Title, accent dot & dismiss button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFD54F))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PRO VIDEO",
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "STUDIO",
                        color = Color(0xFFFFD54F),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { onDismissRequest() }
                        .testTag("cinema_settings_close"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close Pro Video Settings",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            val appearance = com.example.camera.ui.components.LocalFloatingWindowAppearance.current

            if (appearance.showCinemaLutControls) {
                Spacer(modifier = Modifier.height(14.dp))

                // SECTION 1: LOOK & LUT (HOLLYWOOD GRADES)
                CinemaSectionHeader(
                    title = "LOOK & LUT",
                    badge = if (config.selectedLut == CinematicLut.CUSTOM && !config.customLutName.isNullOrBlank()) {
                        config.customLutName ?: "Custom .cube"
                    } else {
                        config.selectedLut.label
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // LUT Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hollywood & Standard Presets
                    CinematicLut.displayPresets.filter { it != CinematicLut.CUSTOM }.forEach { lut ->
                        val isSelected = config.selectedLut == lut
                        CinemaLutChip(
                            label = lut.label,
                            accentColor = lut.accentColor,
                            isSelected = isSelected,
                            onClick = {
                                onConfigChange(
                                    config.copy(
                                        selectedLut = lut,
                                        customLutPath = null,
                                        customLutName = null,
                                        isBakeLutToOutput = true,
                                        isLutPreviewEnabled = true
                                    )
                                )
                            }
                        )
                    }

                    // Custom Imported LUTs
                    customLuts.forEach { customItem ->
                        val isSelected = config.selectedLut == CinematicLut.CUSTOM && config.customLutPath == customItem.filePath
                        CinemaCustomLutChip(
                            item = customItem,
                            isSelected = isSelected,
                            onSelect = {
                                onConfigChange(
                                    config.copy(
                                        selectedLut = CinematicLut.CUSTOM,
                                        customLutPath = customItem.filePath,
                                        customLutName = customItem.title,
                                        isBakeLutToOutput = true,
                                        isLutPreviewEnabled = true
                                    )
                                )
                            },
                            onDelete = {
                                customLutRepo.deleteLut(customItem.id)
                                if (isSelected) {
                                    onConfigChange(
                                        config.copy(
                                            selectedLut = CinematicLut.OFF,
                                            customLutPath = null,
                                            customLutName = null
                                        )
                                    )
                                }
                            }
                        )
                    }

                    // Import .cube Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.07f))
                            .border(1.dp, Color(0xFFFFD54F).copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .clickable { filePickerLauncher.launch(arrayOf("*/*")) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("cinema_import_lut_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Import .cube LUT",
                                tint = Color(0xFFFFD54F),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Import .cube",
                                color = Color(0xFFFFD54F),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // LUT Intensity Slider (0% - 100%) and Bake LUT to Output Toggle
                if (!config.selectedLut.isOff) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "LUT Intensity",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${(config.lutIntensity * 100).toInt()}%",
                                color = Color(0xFFFFD54F),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = config.lutIntensity,
                            onValueChange = { newIntensity ->
                                onConfigChange(config.copy(lutIntensity = newIntensity))
                            },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFFD54F),
                                activeTrackColor = Color(0xFFFFD54F),
                                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cinema_lut_intensity_slider")
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onConfigChange(config.copy(isBakeLutToOutput = !config.isBakeLutToOutput))
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Bake LUT to Video",
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (config.isBakeLutToOutput) "Baked into recorded video" else "Saves pristine Log for color grading",
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = config.isBakeLutToOutput,
                                onCheckedChange = { checked ->
                                    onConfigChange(config.copy(isBakeLutToOutput = checked))
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFFFFD54F),
                                    checkedTrackColor = Color(0xFFFFD54F).copy(alpha = 0.4f),
                                    uncheckedThumbColor = Color.LightGray,
                                    uncheckedTrackColor = Color.White.copy(alpha = 0.2f)
                                ),
                                modifier = Modifier
                                    .height(24.dp)
                                    .testTag("cinema_bake_lut_switch")
                            )
                        }
                    }
                }
            }

            if (appearance.showCinemaColorProfile) {
                Spacer(modifier = Modifier.height(16.dp))

                // SECTION 2: LOG & COLOR PROFILE
                CinemaSectionHeader(title = "COLOR PROFILE & LOG", badge = config.colorProfile.name.replace("_", " "))

                Spacer(modifier = Modifier.height(8.dp))

                // Log Bit Depth Selector (10-bit hidden when selected codec/hardware cannot generate genuine 10-bit)
                val availableDepths = capabilities.getSupportedBitDepthsForCodec(config.codec)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    availableDepths.forEach { depth ->
                        val isSelected = config.logBitDepth == depth
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) Color(0xFFFFD54F).copy(alpha = 0.22f)
                                    else Color.Transparent
                                )
                                .border(
                                    width = if (isSelected) 1.dp else 0.dp,
                                    color = if (isSelected) Color(0xFFFFD54F).copy(alpha = 0.7f) else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onConfigChange(config.copy(logBitDepth = depth)) }
                                .padding(vertical = 7.dp)
                                .testTag("cinema_bit_depth_${depth.name}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (depth) {
                                    LogBitDepth.OFF -> "Off (Linear)"
                                    LogBitDepth.BIT_8 -> "8-bit Log"
                                    LogBitDepth.BIT_10 -> "10-bit Log"
                                },
                                color = if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Color Profile Chips (HLG10 and HDR Log always available)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val allProfiles = listOf(
                        CinemaColorProfile.NATIVE to "Natural",
                        CinemaColorProfile.FLAT_LOG to "Flat Log",
                        CinemaColorProfile.HDR_LOG to "HDR Log",
                        CinemaColorProfile.REC_2020 to "Rec.2020 HDR",
                        CinemaColorProfile.HLG10 to "HLG10 HDR",
                        CinemaColorProfile.APPLE_LOG_2 to "Apple Log 2",
                        CinemaColorProfile.SAMSUNG_APV_LOG to "Samsung APV Log"
                    )
                    allProfiles.filter { capabilities.supportedColorProfiles.contains(it.first) }.forEach { (profile, label) ->
                        val isSelected = config.colorProfile == profile
                        CinemaPillChip(
                            label = label,
                            isSelected = isSelected,
                            onClick = {
                                if (profile == CinemaColorProfile.HLG10) {
                                    // ARIB STD-B67 standard: Rec.2020 gamut. 8-bit default if 10-bit unsupported.
                                    val canDo10Bit = capabilities.getSupportedBitDepthsForCodec(config.codec).contains(LogBitDepth.BIT_10)
                                    onConfigChange(
                                        config.copy(
                                            colorProfile = CinemaColorProfile.HLG10,
                                            colorSpace = CinemaColorSpace.REC_2020,
                                            logBitDepth = if (canDo10Bit) LogBitDepth.BIT_10 else LogBitDepth.BIT_8,
                                            codec = if (config.codec == CinemaCodec.H264 && capabilities.supportedCodecs.contains(CinemaCodec.H265)) CinemaCodec.H265 else config.codec
                                        )
                                    )
                                } else if (profile == CinemaColorProfile.HDR_LOG) {
                                    onConfigChange(
                                        config.copy(
                                            colorProfile = CinemaColorProfile.HDR_LOG,
                                            colorSpace = CinemaColorSpace.REC_709
                                        )
                                    )
                                } else {
                                    onConfigChange(config.copy(colorProfile = profile))
                                }
                            }
                        )
                    }
                }
            }

            if (appearance.showCinemaResolutionFps) {
                Spacer(modifier = Modifier.height(16.dp))

                // SECTION 3: RECORDING FORMAT & CODEC
                CinemaSectionHeader(title = "RECORDING FORMAT", badge = "${config.videoFps} fps • ${config.codec.name}")

                Spacer(modifier = Modifier.height(8.dp))

                // Resolution Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        CameraResolution(3840, 2160) to "4K UHD",
                        CameraResolution(1920, 1080) to "1080p FHD",
                        CameraResolution(1280, 720) to "720p HD"
                    ).forEach { (res, label) ->
                        val isSelected = config.selectedResolution == null && res.width == 3840 ||
                                config.selectedResolution?.width == res.width
                        CinemaPillChip(
                            label = label,
                            isSelected = isSelected,
                            modifier = Modifier.weight(1f),
                            onClick = { onConfigChange(config.copy(selectedResolution = res)) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Framerate & Codec Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // FPS
                    listOf(24 to "24 fps", 30 to "30 fps", 60 to "60 fps").forEach { (fps, label) ->
                        val isSelected = config.videoFps == fps
                        CinemaPillChip(
                            label = label,
                            isSelected = isSelected,
                            modifier = Modifier.weight(1f),
                            onClick = { onConfigChange(config.copy(videoFps = fps)) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Codecs Row (Only actually supported codecs are rendered / enabled)
                val availableCodecs = capabilities.supportedCodecs
                val resWidth = config.selectedResolution?.width ?: 1920
                val resHeight = config.selectedResolution?.height ?: 1080
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableCodecs.forEach { codec ->
                        val isCodecSupportedForCurrentConfig = when (codec) {
                            CinemaCodec.VP9 -> com.example.camera.engine.DeviceCompatibilityManager.isVp9EncodingSupported(
                                width = resWidth,
                                height = resHeight,
                                fps = config.videoFps,
                                requireSurface = true
                            )
                            CinemaCodec.H265 -> com.example.camera.engine.DeviceCompatibilityManager.isHevcEncodingSupported(
                                width = resWidth,
                                height = resHeight,
                                fps = config.videoFps
                            )
                            CinemaCodec.H264 -> true
                            CinemaCodec.PRORES -> true
                        }
                        val isSelected = config.codec == codec
                        CinemaPillChip(
                            label = if (isCodecSupportedForCurrentConfig) codec.label else "${codec.label} (N/A)",
                            isSelected = isSelected && isCodecSupportedForCurrentConfig,
                            enabled = isCodecSupportedForCurrentConfig,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (isCodecSupportedForCurrentConfig) {
                                    val newDepths = capabilities.getSupportedBitDepthsForCodec(codec)
                                    val sanitizedDepth = if (config.logBitDepth == LogBitDepth.BIT_10 && !newDepths.contains(LogBitDepth.BIT_10)) {
                                        LogBitDepth.BIT_8
                                    } else {
                                        config.logBitDepth
                                    }
                                    onConfigChange(config.copy(codec = codec, logBitDepth = sanitizedDepth))
                                }
                            }
                        )
                    }
                }
            }

            if (appearance.showCinemaStabilization) {
                Spacer(modifier = Modifier.height(16.dp))

                // SECTION 4: CAMERA CONTROLS (Live Noise Reduction with guaranteed persistence)
                CinemaSectionHeader(title = "CAMERA CONTROLS", badge = "NR: ${config.noiseReduction.label}")

                Spacer(modifier = Modifier.height(8.dp))

                // Live Noise Reduction Row (OFF stays OFF across restarts)
                Text(
                    text = "Live Noise Reduction",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CinemaNoiseReduction.entries.forEach { nr ->
                        val isSelected = config.noiseReduction == nr
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) Color(0xFFFFD54F).copy(alpha = 0.22f)
                                    else Color.Transparent
                                )
                                .border(
                                    width = if (isSelected) 1.dp else 0.dp,
                                    color = if (isSelected) Color(0xFFFFD54F).copy(alpha = 0.7f) else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onConfigChange(config.copy(noiseReduction = nr)) }
                                .padding(vertical = 7.dp)
                                .testTag("cinema_noise_reduction_${nr.name}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = nr.label,
                                color = if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Sharpness Row
                Text(
                    text = "Sensor Edge Sharpness",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        CinemaSharpness.OFF to "Filmic (Off)",
                        CinemaSharpness.NATURAL to "Natural",
                        CinemaSharpness.CRISP to "Crisp"
                    ).forEach { (sh, label) ->
                        val isSelected = config.sharpness == sh
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) Color(0xFFFFD54F).copy(alpha = 0.22f)
                                    else Color.Transparent
                                )
                                .border(
                                    width = if (isSelected) 1.dp else 0.dp,
                                    color = if (isSelected) Color(0xFFFFD54F).copy(alpha = 0.7f) else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onConfigChange(config.copy(sharpness = sh)) }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            if (appearance.showCinemaAssistTools) {
                Spacer(modifier = Modifier.height(16.dp))

                // SECTION 5: ASSIST TOOLS
                CinemaSectionHeader(title = "ASSIST TOOLS", badge = null)

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CinemaToggleChip(
                        label = "Focus Peaking",
                        isActive = config.isFocusPeakingEnabled,
                        modifier = Modifier.weight(1f),
                        onToggle = { onConfigChange(config.copy(isFocusPeakingEnabled = !config.isFocusPeakingEnabled)) }
                    )
                    CinemaToggleChip(
                        label = "Waveform",
                        isActive = config.isWaveformEnabled,
                        modifier = Modifier.weight(1f),
                        onToggle = { onConfigChange(config.copy(isWaveformEnabled = !config.isWaveformEnabled)) }
                    )
                    CinemaPillChip(
                        label = "Zebras: ${config.zebraThreshold.label}",
                        isSelected = config.zebraThreshold != ZebraThreshold.OFF,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val next = when (config.zebraThreshold) {
                                ZebraThreshold.OFF -> ZebraThreshold.IRE_70
                                ZebraThreshold.IRE_70 -> ZebraThreshold.IRE_100
                                ZebraThreshold.IRE_100 -> ZebraThreshold.OFF
                            }
                            onConfigChange(config.copy(zebraThreshold = next))
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // SECTION 6: COLOR FINE-TUNING SLIDERS
            var showFineTuning by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showFineTuning = !showFineTuning }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val fineTuningStatus = when {
                    showFineTuning -> "Hide"
                    config.hasColorFineTuning -> "Active (Custom)"
                    else -> "Expand"
                }
                CinemaSectionHeader(title = "COLOR FINE-TUNING", badge = fineTuningStatus)
            }

            AnimatedVisibility(visible = showFineTuning) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    // STAGE 1: EXPOSURE & WHITE BALANCE
                    CinemaSubSectionHeader("1. EXPOSURE & WHITE BALANCE")
                    CinemaSliderRow(
                        label = "Live Exposure",
                        value = config.exposure,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(exposure = it)) }
                    )
                    CinemaSliderRow(
                        label = "Temperature",
                        value = config.temperature,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(temperature = it)) }
                    )
                    CinemaSliderRow(
                        label = "Tint",
                        value = config.tint,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(tint = it)) }
                    )

                    // STAGE 2: TONAL ADJUSTMENTS
                    CinemaSubSectionHeader("2. TONAL ADJUSTMENTS")
                    CinemaSliderRow(
                        label = "Whites",
                        value = config.whites,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(whites = it)) }
                    )
                    CinemaSliderRow(
                        label = "Highlights",
                        value = config.highlights,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(highlights = it)) }
                    )
                    CinemaSliderRow(
                        label = "Highlight Roll-off",
                        value = config.highlightRolloff,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(highlightRolloff = it)) }
                    )
                    CinemaSliderRow(
                        label = "Midtones",
                        value = config.midtones,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(midtones = it)) }
                    )
                    CinemaSliderRow(
                        label = "Shadows",
                        value = config.shadows,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(shadows = it)) }
                    )
                    CinemaSliderRow(
                        label = "Shadow Roll-off",
                        value = config.shadowRolloff,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(shadowRolloff = it)) }
                    )
                    CinemaSliderRow(
                        label = "Blacks",
                        value = config.blacks,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(blacks = it)) }
                    )
                    CinemaSliderRow(
                        label = "Black Level",
                        value = config.blackLevel,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(blackLevel = it)) }
                    )
                    CinemaSliderRow(
                        label = "Contrast S-Curve",
                        value = config.contrast,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(contrast = it)) }
                    )
                    CinemaSliderRow(
                        label = "Luma Curve",
                        value = config.lumaCurve,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(lumaCurve = it)) }
                    )
                    CinemaSliderRow(
                        label = "Washed-Out Recovery",
                        value = config.washedOut,
                        valueRange = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(washedOut = it)) }
                    )

                    // STAGE 3: COLOR TRANSFORM
                    CinemaSubSectionHeader("3. COLOR TRANSFORM")
                    CinemaSliderRow(
                        label = "Color Matrix",
                        value = config.colorTransform,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(colorTransform = it)) }
                    )
                    CinemaSliderRow(
                        label = "Chroma Strength",
                        value = config.chromaStrength,
                        valueRange = 0.0f..2.0f,
                        onValueChange = { onConfigChange(config.copy(chromaStrength = it)) }
                    )
                    CinemaSliderRow(
                        label = "Saturation",
                        value = config.saturation,
                        valueRange = 0.0f..2.0f,
                        onValueChange = { onConfigChange(config.copy(saturation = it)) }
                    )
                    CinemaSliderRow(
                        label = "Vibrance",
                        value = config.vibrance,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(vibrance = it)) }
                    )

                    // STAGE 4: TONE MAPPING
                    CinemaSubSectionHeader("4. TONE MAPPING")
                    CinemaSliderRow(
                        label = "Tone Map Strength",
                        value = config.toneMappingStrength,
                        valueRange = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(toneMappingStrength = it)) }
                    )

                    // STAGE 5: NOISE REDUCTION
                    CinemaSubSectionHeader("5. NOISE REDUCTION")
                    CinemaSliderRow(
                        label = "Luma NR",
                        value = config.lumaNoiseReduction,
                        valueRange = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(lumaNoiseReduction = it)) }
                    )
                    CinemaSliderRow(
                        label = "Chroma NR",
                        value = config.chromaNoiseReduction,
                        valueRange = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(chromaNoiseReduction = it)) }
                    )

                    // STAGE 6: DETAIL & SHARPENING
                    CinemaSubSectionHeader("6. DETAIL & SHARPENING")
                    CinemaSliderRow(
                        label = "Sharpening",
                        value = config.fineSharpening,
                        valueRange = 0.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(fineSharpening = it)) }
                    )
                    CinemaSliderRow(
                        label = "Micro-Contrast",
                        value = config.microContrast,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(microContrast = it)) }
                    )
                    CinemaSliderRow(
                        label = "Local Contrast",
                        value = config.localContrast,
                        valueRange = -1.0f..1.0f,
                        onValueChange = { onConfigChange(config.copy(localContrast = it)) }
                    )

                    // STAGE 7: OUTPUT GAMMA
                    CinemaSubSectionHeader("7. OUTPUT GAMMA")
                    CinemaSliderRow(
                        label = "Output Gamma",
                        value = config.outputGamma,
                        valueRange = 0.5f..1.5f,
                        onValueChange = { onConfigChange(config.copy(outputGamma = it)) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .align(Alignment.End)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable {
                                onConfigChange(
                                    config.copy(
                                        exposure = 0.0f,
                                        contrast = 0.0f,
                                        saturation = 1.0f,
                                        washedOut = 0.0f,
                                        shadows = 0.0f,
                                        highlights = 0.0f,
                                        vibrance = 0.0f,
                                        temperature = 0.0f,
                                        tint = 0.0f,
                                        whites = 0.0f,
                                        blacks = 0.0f,
                                        midtones = 0.0f,
                                        blackLevel = 0.0f,
                                        highlightRolloff = 0.0f,
                                        shadowRolloff = 0.0f,
                                        localContrast = 0.0f,
                                        lumaCurve = 0.0f,
                                        colorTransform = 0.0f,
                                        chromaStrength = 1.0f,
                                        lumaNoiseReduction = 0.0f,
                                        chromaNoiseReduction = 0.0f,
                                        fineSharpening = 0.0f,
                                        microContrast = 0.0f,
                                        toneMappingStrength = 0.0f,
                                        outputGamma = 1.0f
                                    )
                                )
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Reset sliders",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Reset All Sliders",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CinemaSubSectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFFFFD54F).copy(alpha = 0.85f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun CinemaSectionHeader(title: String, badge: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = Color(0xFFFFD54F),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        if (badge != null) {
            Text(
                text = badge,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CinemaLutChip(
    label: String,
    accentColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) accentColor.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.06f)
            )
            .border(
                width = if (isSelected) 1.2.dp else 0.5.dp,
                color = if (isSelected) accentColor else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun CinemaCustomLutChip(
    item: CustomLutItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) Color(0xFFAB47BC).copy(alpha = 0.25f)
                else Color.White.copy(alpha = 0.06f)
            )
            .border(
                width = if (isSelected) 1.5.dp else 0.5.dp,
                color = if (isSelected) Color(0xFFAB47BC) else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onSelect() }
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFAB47BC))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = item.title,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Delete custom LUT",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

@Composable
private fun CinemaPillChip(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (!enabled) Color.White.copy(alpha = 0.02f)
                else if (isSelected) Color(0xFFFFD54F).copy(alpha = 0.20f)
                else Color.White.copy(alpha = 0.05f)
            )
            .border(
                width = if (isSelected && enabled) 1.dp else 0.5.dp,
                color = if (!enabled) Color.White.copy(alpha = 0.04f)
                    else if (isSelected) Color(0xFFFFD54F).copy(alpha = 0.7f)
                    else Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (!enabled) Color.White.copy(alpha = 0.25f)
                else if (isSelected) Color(0xFFFFD54F)
                else Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp,
            fontWeight = if (isSelected && enabled) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CinemaToggleChip(
    label: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isActive) Color(0xFFFFD54F).copy(alpha = 0.20f)
                else Color.White.copy(alpha = 0.05f)
            )
            .border(
                width = if (isActive) 1.dp else 0.5.dp,
                color = if (isActive) Color(0xFFFFD54F).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isActive) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color(0xFFFFD54F),
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
            }
            Text(
                text = label,
                color = if (isActive) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.70f),
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CinemaSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp,
            modifier = Modifier.width(115.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFFD54F),
                activeTrackColor = Color(0xFFFFD54F),
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            ),
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
        )
        Text(
            text = String.format("%.2f", value),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(36.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}
