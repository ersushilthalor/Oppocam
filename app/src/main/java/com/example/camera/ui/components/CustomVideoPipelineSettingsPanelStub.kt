package com.example.camera.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.camera.videopipeline.CustomVideoPipelineConfig

@Composable
fun CustomVideoPipelineSettingsPanel(
    config: CustomVideoPipelineConfig = CustomVideoPipelineConfig(),
    onConfigChange: (CustomVideoPipelineConfig) -> Unit = {},
    onResetDefaults: () -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {}
