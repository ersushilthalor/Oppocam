package com.example.camera.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
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
import com.example.camera.data.CustomLutItem
import com.example.camera.model.CinemaConfig
import com.example.camera.model.CinematicLut

/**
 * Dedicated Pro Video Cinematic LUTs Floating Window.
 * Recreates the horizontal floating-stripe design shown in Screenshot_20261005-105536_YouTube~4.jpg:
 * - Tune options button on left
 * - Horizontally swipeable options: Off → Standard → Blockbuster → Thriller → Wedding → Custom...
 * - Active selection highlighted in yellow/gold
 * - Right close 'X' button
 * - Preserves all advanced functions (LUT intensity slider, bake switch, .cube file import) via the tune toggle
 */
@Composable
fun LutFloatingWindow(
    config: CinemaConfig,
    customLuts: List<CustomLutItem> = emptyList(),
    onSelectLut: (CinematicLut) -> Unit,
    onSelectCustomLut: (CustomLutItem) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onBakeToggle: (Boolean) -> Unit,
    onImportLutClick: () -> Unit,
    onDeleteCustomLut: (String) -> Unit = {},
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpandedSettingsOpen by remember { mutableStateOf(false) }

    val builtInPresets = remember {
        listOf(
            CinematicLut.OFF,
            CinematicLut.STANDARD,
            CinematicLut.BLOCKBUSTER,
            CinematicLut.THRILLER,
            CinematicLut.WEDDING
        )
    }

    // Build unified item list
    val allItems = remember(customLuts) {
        val list = mutableListOf<LutSelection>()
        builtInPresets.forEach { list.add(LutSelection.BuiltIn(it)) }
        customLuts.forEach { list.add(LutSelection.Custom(it)) }
        list
    }

    val selectedSelection = remember(config.selectedLut, config.customLutPath, customLuts) {
        if (config.selectedLut == CinematicLut.CUSTOM && config.customLutPath != null) {
            val custom = customLuts.firstOrNull { it.filePath == config.customLutPath }
            if (custom != null) LutSelection.Custom(custom) else LutSelection.BuiltIn(CinematicLut.OFF)
        } else {
            LutSelection.BuiltIn(config.selectedLut)
        }
    }

    HorizontalFloatingSelectorStripe(
        items = allItems,
        selectedItem = selectedSelection,
        itemLabel = { it.title },
        onSelectItem = { selection ->
            when (selection) {
                is LutSelection.BuiltIn -> onSelectLut(selection.lut)
                is LutSelection.Custom -> onSelectCustomLut(selection.item)
            }
        },
        onDismiss = onDismissRequest,
        leadingIcon = Icons.Outlined.Tune,
        leadingIconContentDescription = "LUT Adjustment Options",
        onLeadingIconClick = { isExpandedSettingsOpen = !isExpandedSettingsOpen },
        isLeadingIconActive = isExpandedSettingsOpen,
        testTagPrefix = "lut_floating_window",
        modifier = modifier,
        expandedContent = {
            // Secondary compact drawer for Intensity, Bake toggle, and Import
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Intensity slider
                if (!config.selectedLut.isOff) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Intensity",
                            color = FloatingSelectorDefaults.LabelColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${(config.lutIntensity * 100).toInt()}%",
                            color = FloatingSelectorDefaults.GoldAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = config.lutIntensity,
                        onValueChange = onIntensityChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = FloatingSelectorDefaults.GoldAccent,
                            activeTrackColor = FloatingSelectorDefaults.GoldAccent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .testTag("lut_intensity_slider")
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Bake LUT Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onBakeToggle(!config.isBakeLutToOutput) }
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Bake LUT to Video",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Switch(
                            checked = config.isBakeLutToOutput,
                            onCheckedChange = onBakeToggle,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = FloatingSelectorDefaults.GoldAccent,
                                checkedTrackColor = FloatingSelectorDefaults.GoldAccent.copy(alpha = 0.35f),
                                uncheckedThumbColor = Color.LightGray,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                            ),
                            modifier = Modifier
                                .height(22.dp)
                                .testTag("lut_bake_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Import Custom .cube LUT button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onImportLutClick,
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, FloatingSelectorDefaults.GoldAccent.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("lut_import_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Import .cube LUT",
                                tint = FloatingSelectorDefaults.GoldAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Import .cube",
                                color = FloatingSelectorDefaults.GoldAccent,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Delete current custom LUT if custom is selected
                    if (selectedSelection is LutSelection.Custom) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { onDeleteCustomLut(selectedSelection.item.id) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete custom LUT",
                                tint = Color(0xFFFF6B6B),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    )
}

private sealed class LutSelection {
    abstract val title: String

    data class BuiltIn(val lut: CinematicLut) : LutSelection() {
        override val title: String = lut.label
    }

    data class Custom(val item: CustomLutItem) : LutSelection() {
        override val title: String = item.title
    }
}
