package com.example.camera.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.model.PhotoFilter
import com.example.camera.ui.components.FrostedGlassBox

/**
 * Liquid Glass Floating Photo Filter & Looks Window
 * Consistent with Cinema Mode Liquid Glass styling:
 * - Translucent glass background with specular sheen
 * - Rounded corners (26.dp) and natural depth elevation
 * - Modern, clean photographic looks and film emulation selector
 */
@Composable
fun PhotoFilterSelectorBar(
    selectedFilter: PhotoFilter,
    onFilterSelected: (PhotoFilter) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = Color(0xFF64FFDA) // Emerald / Mint photo look accent

    FrostedGlassBox(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("photo_filter_selector_bar"),
        shape = RoundedCornerShape(26.dp),
        elevation = 20.dp,
        baseAlpha = 0.82f,
        baseTint = Color(0xFF0F121C)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            // Header: Title, accent dot & circular close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PHOTO",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "LOOKS",
                        color = accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Active filter badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentColor.copy(alpha = 0.15f))
                            .border(1.dp, accentColor.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = selectedFilter.displayName,
                            color = accentColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onClose() }
                            .testTag("close_photo_filter_bar"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close Photo Filter Bar",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section: Photographic Looks & Film Emulations
            PhotoSectionHeader(
                title = "FILM & COLOR PROFILES",
                badge = selectedFilter.displayName
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Horizontal Scroll of Liquid Glass Look Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PhotoFilter.entries.forEach { filter ->
                    val isSelected = selectedFilter == filter

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) {
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            accentColor.copy(alpha = 0.25f),
                                            accentColor.copy(alpha = 0.10f)
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.06f),
                                            Color.White.copy(alpha = 0.02f)
                                        )
                                    )
                                }
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) accentColor else Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onFilterSelected(filter) }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                            .testTag("filter_option_${filter.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = filter.displayName,
                            color = if (isSelected) accentColor else Color.White.copy(alpha = 0.85f),
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                            letterSpacing = 0.3.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoSectionHeader(
    title: String,
    badge: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(11.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(Color(0xFF64FFDA))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
        }

        if (badge != null) {
            Text(
                text = badge,
                color = Color(0xFF64FFDA),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
