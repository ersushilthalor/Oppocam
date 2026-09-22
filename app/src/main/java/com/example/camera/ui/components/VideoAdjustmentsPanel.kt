package com.example.camera.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.camera.model.*
import java.util.Locale
import kotlin.math.roundToInt

// Vibrant yellow accent matching the reference screenshots
val AccentYellow = Color(0xFFFFD54F)
val WindowDarkBg = Color(0xF212141A)
val CardDarkBg = Color(0xFF1B1D26)
val UnselectedPillBg = Color(0xFF1E212B)
val SelectedPillBg = Color(0xFF383C4A)
val UnselectedTextColor = Color(0xFF7E8494)

/**
 * Floating Video Adjustments settings window closely matching the reference screenshots.
 * Anchored at the bottom in portrait orientation.
 */
@Composable
fun VideoAdjustmentsPanel(
    adjustments: VideoAdjustments,
    onAdjustmentsChange: (VideoAdjustments) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(VideoAdjustmentsTab.BASIC) }
    var selectedBasicControl by remember { mutableStateOf(BasicAdjustmentControl.EXPOSURE) }
    var advancedSubCategory by remember { mutableStateOf(AdvancedSubCategory.EFFECTS) }
    var effectsSubCategory by remember { mutableStateOf(EffectsSubCategory.LIGHT_FX) }
    var activeAdvancedSlider by remember { mutableStateOf("Bloom") }

    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 20.dp, bottomEnd = 20.dp))
            .background(WindowDarkBg)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.09f),
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
            )
            .testTag("video_adjustments_window")
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag handle / subtle bar at top
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
                    .padding(bottom = 6.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 1. Top Header Row: [ Basic | Advanced ] Segmented Pill and [ ↺ Reset ] Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Segmented control capsule
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF232630))
                        .padding(3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Basic Tab
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(17.dp))
                                .background(if (selectedTab == VideoAdjustmentsTab.BASIC) SelectedPillBg else Color.Transparent)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    selectedTab = VideoAdjustmentsTab.BASIC
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                .padding(horizontal = 18.dp, vertical = 7.dp)
                                .testTag("tab_basic"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.basic),
                                color = if (selectedTab == VideoAdjustmentsTab.BASIC) Color.White else UnselectedTextColor,
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == VideoAdjustmentsTab.BASIC) FontWeight.Bold else FontWeight.Medium
                            )
                        }

                        // Advanced Tab
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(17.dp))
                                .background(if (selectedTab == VideoAdjustmentsTab.ADVANCED) SelectedPillBg else Color.Transparent)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    selectedTab = VideoAdjustmentsTab.ADVANCED
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                .padding(horizontal = 18.dp, vertical = 7.dp)
                                .testTag("tab_advanced"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.advanced),
                                color = if (selectedTab == VideoAdjustmentsTab.ADVANCED) Color.White else UnselectedTextColor,
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == VideoAdjustmentsTab.ADVANCED) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                // Reset Button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF232630))
                        .clickable {
                            onReset()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                        .testTag("video_adjustments_reset"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = stringResource(R.string.reset),
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.reset),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 2. Tab Content
            when (selectedTab) {
                VideoAdjustmentsTab.BASIC -> {
                    BasicAdjustmentsView(
                        adjustments = adjustments,
                        selectedControl = selectedBasicControl,
                        onControlSelected = { control ->
                            selectedBasicControl = control
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onValueChange = { control, newVal ->
                            onAdjustmentsChange(adjustments.withValue(control, newVal))
                        }
                    )
                }
                VideoAdjustmentsTab.ADVANCED -> {
                    AdvancedAdjustmentsView(
                        adjustments = adjustments,
                        subCategory = advancedSubCategory,
                        onSubCategorySelected = {
                            advancedSubCategory = it
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        effectsCategory = effectsSubCategory,
                        onEffectsCategorySelected = {
                            effectsSubCategory = it
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        activeSlider = activeAdvancedSlider,
                        onActiveSliderChange = { activeAdvancedSlider = it },
                        onAdjustmentsChange = onAdjustmentsChange
                    )
                }
            }
        }
    }
}

/**
 * 4-column Basic Adjustments Layout + Bottom Slider Card matching Screenshot 1.
 */
@Composable
private fun BasicAdjustmentsView(
    adjustments: VideoAdjustments,
    selectedControl: BasicAdjustmentControl,
    onControlSelected: (BasicAdjustmentControl) -> Unit,
    onValueChange: (BasicAdjustmentControl, Float) -> Unit
) {
    // 4-column grid of 3 rows:
    // Row 1: Tonality | Saturation | Contrast | Exposure
    // Row 2: Temp | Tint | Highlights | Shadows
    // Row 3: Vignette | Grain | Clarity | Sharpness
    val rows = listOf(
        listOf(BasicAdjustmentControl.TONALITY, BasicAdjustmentControl.SATURATION, BasicAdjustmentControl.CONTRAST, BasicAdjustmentControl.EXPOSURE),
        listOf(BasicAdjustmentControl.TEMP, BasicAdjustmentControl.TINT, BasicAdjustmentControl.HIGHLIGHTS, BasicAdjustmentControl.SHADOWS),
        listOf(BasicAdjustmentControl.VIGNETTE, BasicAdjustmentControl.GRAIN, BasicAdjustmentControl.CLARITY, BasicAdjustmentControl.SHARPNESS)
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { rowIndex, rowControls ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                rowControls.forEach { control ->
                    val isSelected = (control == selectedControl)
                    val value = adjustments.getValue(control)

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onControlSelected(control)
                            }
                            .testTag("basic_control_${control.name.lowercase()}"),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Large numeric value
                        Text(
                            text = control.formatValue(value),
                            color = if (isSelected) AccentYellow else Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(3.dp))

                        // Control Label
                        Text(
                            text = control.label,
                            color = if (isSelected) AccentYellow else UnselectedTextColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(3.dp))

                        // Yellow underline pill indicator for selected control
                        Box(
                            modifier = Modifier
                                .size(width = 22.dp, height = 2.5.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(if (isSelected) AccentYellow else Color.Transparent)
                        )
                    }
                }
            }

            if (rowIndex < rows.size - 1) {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Floating Slider Card for Currently Selected Control
        key(selectedControl) {
            val currentValue = adjustments.getValue(selectedControl)
            SliderCard(
                sliderKey = selectedControl,
                label = selectedControl.label,
                valueString = selectedControl.formatValue(currentValue),
                value = currentValue,
                min = selectedControl.min,
                max = selectedControl.max,
                isDecimal = selectedControl.isDecimal,
                onValueChange = { newVal ->
                    onValueChange(selectedControl, newVal)
                },
                isActive = true
            )
        }
    }
}

/**
 * Advanced Adjustments Layout matching Screenshot 2.
 */
@Composable
private fun AdvancedAdjustmentsView(
    adjustments: VideoAdjustments,
    subCategory: AdvancedSubCategory,
    onSubCategorySelected: (AdvancedSubCategory) -> Unit,
    effectsCategory: EffectsSubCategory,
    onEffectsCategorySelected: (EffectsSubCategory) -> Unit,
    activeSlider: String,
    onActiveSliderChange: (String) -> Unit,
    onAdjustmentsChange: (VideoAdjustments) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Sub-Category Pills: [ Curve ]  [ Color ]  [ Effects ]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AdvancedSubCategory.values().forEach { cat ->
                val isSelected = (cat == subCategory)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) Color(0xFF2D313E) else UnselectedPillBg)
                        .clickable { onSubCategorySelected(cat) }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                        .testTag("adv_tab_${cat.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = cat.label,
                        color = if (isSelected) Color.White else UnselectedTextColor,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (subCategory) {
            AdvancedSubCategory.EFFECTS -> {
                // Effects Toggle: [ Light FX | Texture ]
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E212B))
                        .padding(3.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        EffectsSubCategory.values().forEach { eff ->
                            val isSelected = (eff == effectsCategory)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) SelectedPillBg else Color.Transparent)
                                    .clickable { onEffectsCategorySelected(eff) }
                                    .padding(vertical = 10.dp)
                                    .testTag("effects_toggle_${eff.name.lowercase()}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = eff.label,
                                    color = if (isSelected) Color.White else UnselectedTextColor,
                                    fontSize = 13.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (effectsCategory == EffectsSubCategory.LIGHT_FX) {
                    // Light FX: Flash, Bloom, Soft Light (matching Screenshot 2!)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SliderCard(
                            label = "Flash",
                            valueString = "${adjustments.lightFxFlash.roundToInt()}",
                            value = adjustments.lightFxFlash,
                            min = 0f,
                            max = 100f,
                            isActive = (activeSlider == "Flash"),
                            onValueChange = {
                                onActiveSliderChange("Flash")
                                onAdjustmentsChange(adjustments.copy(lightFxFlash = it))
                            }
                        )

                        SliderCard(
                            label = "Bloom",
                            valueString = "${adjustments.lightFxBloom.roundToInt()}",
                            value = adjustments.lightFxBloom,
                            min = 0f,
                            max = 100f,
                            isActive = (activeSlider == "Bloom"),
                            onValueChange = {
                                onActiveSliderChange("Bloom")
                                onAdjustmentsChange(adjustments.copy(lightFxBloom = it))
                            }
                        )

                        SliderCard(
                            label = "Soft Light",
                            valueString = "${adjustments.lightFxSoftLight.roundToInt()}",
                            value = adjustments.lightFxSoftLight,
                            min = 0f,
                            max = 100f,
                            isActive = (activeSlider == "Soft Light"),
                            onValueChange = {
                                onActiveSliderChange("Soft Light")
                                onAdjustmentsChange(adjustments.copy(lightFxSoftLight = it))
                            }
                        )
                    }
                } else {
                    // Texture: Film Grain, Halation, Micro-Contrast
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SliderCard(
                            label = "Film Grain",
                            valueString = "${adjustments.textureFilmGrain.roundToInt()}",
                            value = adjustments.textureFilmGrain,
                            min = 0f,
                            max = 100f,
                            isActive = (activeSlider == "Film Grain"),
                            onValueChange = {
                                onActiveSliderChange("Film Grain")
                                onAdjustmentsChange(adjustments.copy(textureFilmGrain = it))
                            }
                        )

                        SliderCard(
                            label = "Halation",
                            valueString = "${adjustments.textureHalation.roundToInt()}",
                            value = adjustments.textureHalation,
                            min = 0f,
                            max = 100f,
                            isActive = (activeSlider == "Halation"),
                            onValueChange = {
                                onActiveSliderChange("Halation")
                                onAdjustmentsChange(adjustments.copy(textureHalation = it))
                            }
                        )

                        SliderCard(
                            label = "Micro-Contrast",
                            valueString = "${adjustments.textureMicroContrast.roundToInt()}",
                            value = adjustments.textureMicroContrast,
                            min = 0f,
                            max = 100f,
                            isActive = (activeSlider == "Micro-Contrast"),
                            onValueChange = {
                                onActiveSliderChange("Micro-Contrast")
                                onAdjustmentsChange(adjustments.copy(textureMicroContrast = it))
                            }
                        )
                    }
                }
            }

            AdvancedSubCategory.CURVE -> {
                // Interactive Tone Curve Visualization + 5 Curve Points
                ToneCurveCanvas(
                    blacks = adjustments.curveBlacks,
                    shadows = adjustments.curveShadows,
                    midtones = adjustments.curveMidtones,
                    highlights = adjustments.curveHighlights,
                    whites = adjustments.curveWhites
                )

                Spacer(modifier = Modifier.height(14.dp))

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SliderCard(
                        label = "Blacks",
                        valueString = "${adjustments.curveBlacks.roundToInt()}",
                        value = adjustments.curveBlacks,
                        min = -100f,
                        max = 100f,
                        isActive = (activeSlider == "Blacks"),
                        onValueChange = {
                            onActiveSliderChange("Blacks")
                            onAdjustmentsChange(adjustments.copy(curveBlacks = it))
                        }
                    )
                    SliderCard(
                        label = "Shadows",
                        valueString = "${adjustments.curveShadows.roundToInt()}",
                        value = adjustments.curveShadows,
                        min = -100f,
                        max = 100f,
                        isActive = (activeSlider == "Shadows"),
                        onValueChange = {
                            onActiveSliderChange("Shadows")
                            onAdjustmentsChange(adjustments.copy(curveShadows = it))
                        }
                    )
                    SliderCard(
                        label = "Midtones",
                        valueString = "${adjustments.curveMidtones.roundToInt()}",
                        value = adjustments.curveMidtones,
                        min = -100f,
                        max = 100f,
                        isActive = (activeSlider == "Midtones"),
                        onValueChange = {
                            onActiveSliderChange("Midtones")
                            onAdjustmentsChange(adjustments.copy(curveMidtones = it))
                        }
                    )
                    SliderCard(
                        label = "Highlights",
                        valueString = "${adjustments.curveHighlights.roundToInt()}",
                        value = adjustments.curveHighlights,
                        min = -100f,
                        max = 100f,
                        isActive = (activeSlider == "Highlights"),
                        onValueChange = {
                            onActiveSliderChange("Highlights")
                            onAdjustmentsChange(adjustments.copy(curveHighlights = it))
                        }
                    )
                    SliderCard(
                        label = "Whites",
                        valueString = "${adjustments.curveWhites.roundToInt()}",
                        value = adjustments.curveWhites,
                        min = -100f,
                        max = 100f,
                        isActive = (activeSlider == "Whites"),
                        onValueChange = {
                            onActiveSliderChange("Whites")
                            onAdjustmentsChange(adjustments.copy(curveWhites = it))
                        }
                    )
                }
            }

            AdvancedSubCategory.COLOR -> {
                // Color Adjustments: Vibrance, Red-Cyan, Green-Magenta, Blue-Yellow
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SliderCard(
                        label = "Vibrance",
                        valueString = "${adjustments.colorVibrance.roundToInt()}",
                        value = adjustments.colorVibrance,
                        min = -100f,
                        max = 100f,
                        isActive = (activeSlider == "Vibrance"),
                        onValueChange = {
                            onActiveSliderChange("Vibrance")
                            onAdjustmentsChange(adjustments.copy(colorVibrance = it))
                        }
                    )
                    SliderCard(
                        label = "Red / Cyan",
                        valueString = "${adjustments.colorBalanceR.roundToInt()}",
                        value = adjustments.colorBalanceR,
                        min = -100f,
                        max = 100f,
                        isActive = (activeSlider == "Red / Cyan"),
                        onValueChange = {
                            onActiveSliderChange("Red / Cyan")
                            onAdjustmentsChange(adjustments.copy(colorBalanceR = it))
                        }
                    )
                    SliderCard(
                        label = "Green / Magenta",
                        valueString = "${adjustments.colorBalanceG.roundToInt()}",
                        value = adjustments.colorBalanceG,
                        min = -100f,
                        max = 100f,
                        isActive = (activeSlider == "Green / Magenta"),
                        onValueChange = {
                            onActiveSliderChange("Green / Magenta")
                            onAdjustmentsChange(adjustments.copy(colorBalanceG = it))
                        }
                    )
                    SliderCard(
                        label = "Blue / Yellow",
                        valueString = "${adjustments.colorBalanceB.roundToInt()}",
                        value = adjustments.colorBalanceB,
                        min = -100f,
                        max = 100f,
                        isActive = (activeSlider == "Blue / Yellow"),
                        onValueChange = {
                            onActiveSliderChange("Blue / Yellow")
                            onAdjustmentsChange(adjustments.copy(colorBalanceB = it))
                        }
                    )
                }
            }
        }
    }
}

/**
 * Reusable Slider Card with Tick-Mark Ruler and Yellow Needle matching Reference Screenshots.
 */
@Composable
fun SliderCard(
    label: String,
    valueString: String,
    value: Float,
    min: Float,
    max: Float,
    isDecimal: Boolean = false,
    isActive: Boolean = true,
    sliderKey: Any? = label,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardDarkBg)
            .border(
                width = 1.dp,
                color = if (isActive) AccentYellow.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row: Dot • Label ... Value
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isActive) AccentYellow else Color.White.copy(alpha = 0.5f))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = valueString,
                    color = if (isActive) AccentYellow else Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Custom Tick Ruler Slider
            RulerTickSlider(
                sliderKey = sliderKey ?: label,
                value = value,
                min = min,
                max = max,
                isDecimal = isDecimal,
                isActive = isActive,
                onValueChange = onValueChange
            )
        }
    }
}

/**
 * Ruler with evenly-spaced tick marks and draggable/tappable yellow needle indicator.
 */
@Composable
fun RulerTickSlider(
    value: Float,
    min: Float,
    max: Float,
    isDecimal: Boolean,
    isActive: Boolean,
    sliderKey: Any? = null,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentMin by rememberUpdatedState(min)
    val currentMax by rememberUpdatedState(max)
    val currentIsDecimal by rememberUpdatedState(isDecimal)
    var lastHapticValue by remember(sliderKey) { mutableStateOf(value) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(sliderKey, min, max, isDecimal) {
                detectTapGestures { offset ->
                    val norm = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val rawVal = currentMin + norm * (currentMax - currentMin)
                    val rounded = if (currentIsDecimal) {
                        (rawVal * 10f).roundToInt() / 10f
                    } else {
                        rawVal.roundToInt().toFloat()
                    }
                    currentOnValueChange(rounded)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            .pointerInput(sliderKey, min, max, isDecimal) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val norm = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val rawVal = currentMin + norm * (currentMax - currentMin)
                    val rounded = if (currentIsDecimal) {
                        (rawVal * 10f).roundToInt() / 10f
                    } else {
                        rawVal.roundToInt().toFloat()
                    }
                    if (rounded != lastHapticValue) {
                        lastHapticValue = rounded
                        if (rounded == 0f || Math.abs(rounded % 10f) < 0.1f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                    currentOnValueChange(rounded)
                }
            }
    ) {
        val totalWidth = constraints.maxWidth.toFloat()
        val totalHeight = constraints.maxHeight.toFloat()
        val normPos = ((value - min) / (max - min)).coerceIn(0f, 1f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerY = totalHeight / 2f
            val numTicks = 21
            val tickStep = totalWidth / (numTicks - 1).toFloat()

            // 1. Baseline subtle track
            drawLine(
                color = Color.White.copy(alpha = 0.12f),
                start = Offset(0f, centerY),
                end = Offset(totalWidth, centerY),
                strokeWidth = 1.dp.toPx()
            )

            // 2. Vertical Tick Marks
            for (i in 0 until numTicks) {
                val tickX = i * tickStep
                val isCenter = (i == numTicks / 2)
                val isMajor = (i % 5 == 0)

                val tickHeight = when {
                    isCenter -> 16.dp.toPx()
                    isMajor -> 11.dp.toPx()
                    else -> 6.dp.toPx()
                }

                val tickColor = when {
                    isCenter -> Color(0xFFA0A6B8)
                    isMajor -> Color(0xFF6B7285)
                    else -> Color(0xFF383D4C)
                }

                drawLine(
                    color = tickColor,
                    start = Offset(tickX, centerY - tickHeight / 2f),
                    end = Offset(tickX, centerY + tickHeight / 2f),
                    strokeWidth = (if (isCenter) 2.dp else 1.2.dp).toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 3. Active Yellow Needle
            val needleX = (normPos * totalWidth).coerceIn(4f, totalWidth - 4f)
            val needleHeight = 22.dp.toPx()
            val needleWidth = 3.dp.toPx()

            drawRoundRect(
                color = AccentYellow,
                topLeft = Offset(needleX - needleWidth / 2f, centerY - needleHeight / 2f),
                size = Size(needleWidth, needleHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
            )
        }
    }
}

/**
 * Interactive Tone Curve Canvas representation for the Curve sub-category.
 */
@Composable
fun ToneCurveCanvas(
    blacks: Float,
    shadows: Float,
    midtones: Float,
    highlights: Float,
    whites: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CardDarkBg)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Grid lines
            for (i in 1..3) {
                val gx = w * (i / 4f)
                val gy = h * (i / 4f)
                drawLine(Color.White.copy(alpha = 0.06f), Offset(gx, 0f), Offset(gx, h), 1f)
                drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, gy), Offset(w, gy), 1f)
            }

            // Diagonal identity guide
            drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, h), Offset(w, 0f), 1f)

            // Dynamic curve points
            val p0 = Offset(0f, h - (0f + (blacks / 100f) * 0.25f) * h)
            val p1 = Offset(w * 0.25f, h - (0.25f + (shadows / 100f) * 0.20f) * h)
            val p2 = Offset(w * 0.50f, h - (0.50f + (midtones / 100f) * 0.20f) * h)
            val p3 = Offset(w * 0.75f, h - (0.75f + (highlights / 100f) * 0.20f) * h)
            val p4 = Offset(w, h - (1.00f + (whites / 100f) * 0.25f) * h)

            val curvePath = Path().apply {
                moveTo(p0.x, p0.y.coerceIn(0f, h))
                cubicTo(
                    p0.x + (p1.x - p0.x) * 0.5f, p0.y.coerceIn(0f, h),
                    p1.x - (p2.x - p1.x) * 0.3f, p1.y.coerceIn(0f, h),
                    p1.x, p1.y.coerceIn(0f, h)
                )
                cubicTo(
                    p1.x + (p2.x - p1.x) * 0.3f, p1.y.coerceIn(0f, h),
                    p2.x - (p3.x - p2.x) * 0.3f, p2.y.coerceIn(0f, h),
                    p2.x, p2.y.coerceIn(0f, h)
                )
                cubicTo(
                    p2.x + (p3.x - p2.x) * 0.3f, p2.y.coerceIn(0f, h),
                    p3.x - (p4.x - p3.x) * 0.3f, p3.y.coerceIn(0f, h),
                    p3.x, p3.y.coerceIn(0f, h)
                )
                cubicTo(
                    p3.x + (p4.x - p3.x) * 0.5f, p3.y.coerceIn(0f, h),
                    p4.x, p4.y.coerceIn(0f, h),
                    p4.x, p4.y.coerceIn(0f, h)
                )
            }

            drawPath(
                path = curvePath,
                color = AccentYellow,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )

            // Draw control points
            listOf(p0, p1, p2, p3, p4).forEach { pt ->
                drawCircle(Color(0xFF1B1D26), radius = 5.dp.toPx(), center = Offset(pt.x, pt.y.coerceIn(0f, h)))
                drawCircle(AccentYellow, radius = 3.5.dp.toPx(), center = Offset(pt.x, pt.y.coerceIn(0f, h)))
            }
        }
    }
}
