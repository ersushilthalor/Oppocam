package com.example.camera.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared styling constants matching the reference floating selector UI.
 */
object FloatingSelectorDefaults {
    val GoldAccent = Color(0xFFFFC107) // Yellow / Gold accent for active selections
    val LabelColor = Color.White.copy(alpha = 0.72f) // Muted label color (Size, FPS)
    val TextColor = Color.White // Unselected item color
    val DescColor = Color.White.copy(alpha = 0.85f) // Bottom descriptive text
    val CardShape = RoundedCornerShape(26.dp)
    val BaseTint = Color(0xFF14161E)
}

/**
 * Common reusable container for all horizontal floating selector windows (Resolution, LUT, LOG).
 * Matches the frosted glass aesthetic, rounded corners, and compact proportions shown in the reference screenshots.
 */
@Composable
fun HorizontalFloatingSelectorCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = FloatingSelectorDefaults.CardShape,
    content: @Composable BoxScope.() -> Unit
) {
    FrostedGlassBox(
        modifier = modifier
            .wrapContentWidth()
            .widthIn(min = 280.dp, max = 340.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = shape,
        elevation = 14.dp,
        baseAlpha = 0.88f,
        baseTint = FloatingSelectorDefaults.BaseTint,
        borderWidth = 1.dp,
        borderColor = Color.White.copy(alpha = 0.09f),
        showTopHighlightRim = true
    ) {
        content()
    }
}

/**
 * Common reusable horizontal floating stripe component used for LUT and LOG selectors.
 * Matches the horizontal capsule design shown in Screenshot_20261005-105536_YouTube~4.jpg:
 * - Left circle button with tune/options icon
 * - Horizontally scrollable row of clean typography options (no chip borders)
 * - Active option highlighted in yellow/gold bold
 * - Right close 'X' button
 * - Optional expandable secondary drawer (for LUT intensity / Log bit depth)
 */
@Composable
fun <T> HorizontalFloatingSelectorStripe(
    items: List<T>,
    selectedItem: T,
    itemLabel: (T) -> String,
    onSelectItem: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = Icons.Outlined.Tune,
    leadingIconContentDescription: String? = "Settings",
    onLeadingIconClick: (() -> Unit)? = null,
    isLeadingIconActive: Boolean = false,
    testTagPrefix: String = "stripe_option",
    expandedContent: (@Composable () -> Unit)? = null
) {
    HorizontalFloatingSelectorCard(
        modifier = modifier.testTag("${testTagPrefix}_container")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Left circular icon button (e.g. Tune / Options)
                if (leadingIcon != null) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                if (isLeadingIconActive) FloatingSelectorDefaults.GoldAccent.copy(alpha = 0.25f)
                                else Color.White.copy(alpha = 0.12f)
                            )
                            .clickable(
                                enabled = onLeadingIconClick != null,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true, radius = 17.dp)
                            ) {
                                onLeadingIconClick?.invoke()
                            }
                            .testTag("${testTagPrefix}_leading_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = leadingIconContentDescription,
                            tint = if (isLeadingIconActive) FloatingSelectorDefaults.GoldAccent else Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }

                // 2. Horizontally scrollable row of options
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items.forEach { item ->
                        val isSelected = item == selectedItem
                        Text(
                            text = itemLabel(item),
                            color = if (isSelected) FloatingSelectorDefaults.GoldAccent else FloatingSelectorDefaults.TextColor,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            letterSpacing = 0.2.sp,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = false, radius = 24.dp)
                                ) {
                                    onSelectItem(item)
                                }
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                                .testTag("${testTagPrefix}_${itemLabel(item).lowercase().replace(' ', '_')}")
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 3. Right circular close 'X' button
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, radius = 16.dp)
                        ) { onDismiss() }
                        .testTag("${testTagPrefix}_close_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 4. Secondary collapsible drawer (e.g. intensity slider, bit depth)
            AnimatedVisibility(
                visible = isLeadingIconActive && expandedContent != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (expandedContent != null) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        expandedContent()
                    }
                }
            }
        }
    }
}
