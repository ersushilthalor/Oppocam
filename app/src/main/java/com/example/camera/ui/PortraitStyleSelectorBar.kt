package com.example.camera.ui

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
import androidx.compose.material.icons.outlined.FaceRetouchingNatural
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import com.example.camera.model.PortraitStyle
import com.example.camera.ui.components.FrostedGlassBox

@Composable
fun PortraitStyleSelectorBar(
    selectedStyle: PortraitStyle,
    onStyleSelected: (PortraitStyle) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val portraitAccent = Color(0xFFFFD54F) // Master camera gold accent

    FrostedGlassBox(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("portrait_style_selector_bar"),
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(portraitAccent)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PORTRAIT",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "STYLES",
                        color = portraitAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(portraitAccent.copy(alpha = 0.15f))
                            .border(1.dp, portraitAccent.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = selectedStyle.displayName,
                            color = portraitAccent,
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
                            .testTag("close_portrait_style_bar"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close style bar",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Style Pills Horizontal Scroll
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PortraitStyle.entries.forEach { style ->
                    val isSelected = selectedStyle == style

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) {
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            portraitAccent.copy(alpha = 0.25f),
                                            portraitAccent.copy(alpha = 0.10f)
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
                                color = if (isSelected) portraitAccent else Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onStyleSelected(style) }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                            .testTag("style_option_${style.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = style.displayName,
                            color = if (isSelected) portraitAccent else Color.White.copy(alpha = 0.85f),
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
