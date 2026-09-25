package com.example.camera.engine

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.SizeF
import com.example.camera.model.LensInfo
import com.example.camera.model.LensType

private const val TAG = "CameraDiscovery"

/**
 * Camera Discovery & Lens Classification Engine.
 * Adapted from PhotonCamera's camera discovery architecture.
 *
 * Provides a reliable camera inventory that:
 * - Discovers available cameras using Camera2 API.
 * - Identifies logical cameras and their advertised physical camera IDs.
 * - Distinguishes independent cameras from physical lenses inside logical cameras.
 * - Maps lens types using actual camera characteristics.
 * - Avoids hardcoded camera IDs and assumptions about device hardware.
 * - Prevents duplicate or incorrectly classified lens entries.
 */
data class CameraInventory(
    val lenses: List<LensInfo>,
    val logicalToPhysicalMap: Map<String, Set<String>> = emptyMap(),
    val physicalToLogicalMap: Map<String, String> = emptyMap(),
    val characteristicsCache: Map<String, CameraCharacteristics> = emptyMap()
)

object CameraDiscovery {

    fun discover(cameraManager: CameraManager?): CameraInventory {
        if (cameraManager == null) {
            return CameraInventory(emptyList())
        }

        try {
            val officialIds = try {
                cameraManager.cameraIdList.toList()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to get cameraIdList", t)
                emptyList()
            }

            val characteristicsMap = mutableMapOf<String, CameraCharacteristics>()
            fun getChars(id: String): CameraCharacteristics? {
                return characteristicsMap.getOrPut(id) {
                    try {
                        cameraManager.getCameraCharacteristics(id)
                    } catch (t: Throwable) {
                        return null
                    }
                }
            }

            // Candidate camera IDs: start with official IDs from CameraManager
            val candidateIds = linkedSetOf<String>()
            candidateIds.addAll(officialIds)

            // Probe vendor auxiliary cameras safely (0..12) that might not be in official cameraIdList
            for (idNum in 0..12) {
                val sId = idNum.toString()
                if (!candidateIds.contains(sId)) {
                    val chars = getChars(sId)
                    if (chars != null) {
                        candidateIds.add(sId)
                    }
                }
            }

            val logicalToPhysical = mutableMapOf<String, Set<String>>()
            val physicalToLogical = mutableMapOf<String, String>()

            // Identify Logical Multi-Cameras (API 28+)
            for (id in candidateIds) {
                val chars = getChars(id) ?: continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    val isLogical = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                    if (isLogical) {
                        val physIds = chars.physicalCameraIds
                        if (physIds.isNotEmpty()) {
                            logicalToPhysical[id] = physIds
                            for (pId in physIds) {
                                physicalToLogical[pId] = id
                                getChars(pId) // Pre-cache characteristics
                            }
                        }
                    }
                }
            }

            // Find Primary Rear Camera
            val primaryBackId = candidateIds.firstOrNull { id ->
                getChars(id)?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: candidateIds.firstOrNull() ?: "0"

            val primaryFrontId = candidateIds.firstOrNull { id ->
                getChars(id)?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: "1"

            val primaryBackChars = getChars(primaryBackId)
            val isPrimaryBackLogical = logicalToPhysical.containsKey(primaryBackId)

            val primaryBackZoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                primaryBackChars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else null
            val primaryBackMinZoom = primaryBackZoomRange?.lower ?: 1.0f
            val primaryBackMaxZoom = primaryBackZoomRange?.upper ?: 10.0f
            val hasLogicalUltraWide = primaryBackMinZoom < 0.95f

            // Baseline Main Sensor dimensions
            val primaryFocals = primaryBackChars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(4.5f)
            val primaryApertures = primaryBackChars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.8f)
            val primarySensorSize = primaryBackChars?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: SizeF(6.4f, 4.8f)
            val primaryDiag = if (primarySensorSize.width > 0 && primarySensorSize.height > 0) {
                kotlin.math.sqrt((primarySensorSize.width * primarySensorSize.width + primarySensorSize.height * primarySensorSize.height).toDouble()).toFloat()
            } else 8.0f
            val primaryCropFactor = if (primaryDiag > 0f) 43.27f / primaryDiag else 6.0f

            // Find true primary main focal length (~24-28mm eq35, around 4-6.5mm)
            val primaryMainFocal = primaryFocals.filter { (it * primaryCropFactor) in 21f..36f }
                .minByOrNull { kotlin.math.abs(it * primaryCropFactor - 26f) }
                ?: primaryFocals.firstOrNull()
                ?: 4.5f
            val primaryMainEq35 = primaryMainFocal * primaryCropFactor
            val primaryMainSensorWidth = primarySensorSize.width
            val primaryMainFov = CameraOpticalCalibration.calculateHorizontalFovDegrees(primaryMainSensorWidth, primaryMainFocal)
            val primaryMainAperture = primaryApertures.firstOrNull() ?: 1.8f

            val lenses = mutableListOf<LensInfo>()
            val registeredLensesTypes = mutableSetOf<String>()

            // 1. Primary Back Main Wide (1x)
            lenses.add(
                LensInfo(
                    cameraId = primaryBackId,
                    facing = CameraCharacteristics.LENS_FACING_BACK,
                    lensType = LensType.WIDE,
                    displayName = "1x Main (${primaryMainFocal}mm f/${primaryMainAperture})",
                    focalLengthMm = primaryMainFocal,
                    maxAperture = primaryMainAperture,
                    isPhysical = true,
                    isHiddenAux = false,
                    isZoomPreset = false,
                    baseZoomRatio = 1.0f,
                    minZoomRatio = primaryBackMinZoom,
                    maxZoomRatio = primaryBackMaxZoom,
                    isLogicalMultiCamera = isPrimaryBackLogical,
                    isPrimaryMain = true,
                    isIndependentCamera = true,
                    supportsPhysicalStream = false,
                    intrinsicZoomRatio = 1.0f,
                    fovDegrees = primaryMainFov,
                    equivalent35mmFocalMm = primaryMainEq35,
                    idTypeDescription = if (isPrimaryBackLogical) "Logical Multi-Cam Main (1x)" else "Primary Main Camera (1x)"
                )
            )
            registeredLensesTypes.add("BACK_WIDE")

            // 2. Discover Physical Lenses within Logical Multi-Camera (API 28+)
            val physicalIdsForMain = logicalToPhysical[primaryBackId] ?: emptySet()
            var detectedPhysicalUltraWide = false

            for (physId in physicalIdsForMain) {
                val physChars = getChars(physId) ?: continue
                val facing = physChars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val pFocals = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(4f)
                val pApertures = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.8f)
                val pSensor = physChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: SizeF(5.0f, 3.8f)
                val pMinFocus = physChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

                val pFocal = pFocals.firstOrNull() ?: 4f
                val pAperture = pApertures.firstOrNull() ?: 1.8f
                val pDiag = if (pSensor.width > 0 && pSensor.height > 0) {
                    kotlin.math.sqrt((pSensor.width * pSensor.width + pSensor.height * pSensor.height).toDouble()).toFloat()
                } else 6.0f
                val pCrop = if (pDiag > 0f) 43.27f / pDiag else 6.0f
                val pEq35 = pFocal * pCrop
                val pFov = CameraOpticalCalibration.calculateHorizontalFovDegrees(pSensor.width, pFocal)

                val isUltraWide = (pEq35 in 1.0f..20.0f) || pFocal <= 2.8f || pFov >= 95.0f
                val isTele3x = pEq35 >= 70f || pFocal >= 9.0f
                val isTele2x = (pEq35 in 45f..70f || pFocal in 5.8f..9.0f) && !isTele3x
                val isMacro = pMinFocus > 10f && pFocal < 3.2f

                val pType = when {
                    isUltraWide -> LensType.ULTRAWIDE
                    isTele3x -> LensType.TELEPHOTO_3X
                    isTele2x -> LensType.TELEPHOTO
                    isMacro -> LensType.MACRO
                    else -> LensType.WIDE
                }

                if (pType == LensType.WIDE) continue // Main wide already registered

                if (pType == LensType.ULTRAWIDE) {
                    detectedPhysicalUltraWide = true
                }

                val opticalRatio = CameraOpticalCalibration.calculateCalibratedOpticalRatio(
                    lensFocalLengthMm = pFocal,
                    lensSensorWidthMm = pSensor.width,
                    mainFocalLengthMm = primaryMainFocal,
                    mainSensorWidthMm = primaryMainSensorWidth,
                    lensType = pType,
                    logicalMinZoomRatio = if (hasLogicalUltraWide) primaryBackMinZoom else null
                )

                val key = "BACK_${pType.name}"
                if (!registeredLensesTypes.contains(key)) {
                    registeredLensesTypes.add(key)
                    lenses.add(
                        LensInfo(
                            cameraId = primaryBackId, // Openable device is logical multi-camera
                            facing = CameraCharacteristics.LENS_FACING_BACK,
                            lensType = pType,
                            displayName = "${opticalRatio}x ${pType.shortLabel} (${pFocal}mm f/${pAperture})",
                            focalLengthMm = pFocal,
                            maxAperture = pAperture,
                            isPhysical = true,
                            isHiddenAux = false,
                            isZoomPreset = false,
                            baseZoomRatio = opticalRatio,
                            minZoomRatio = primaryBackMinZoom,
                            maxZoomRatio = primaryBackMaxZoom,
                            isLogicalMultiCamera = true,
                            isIndependentCamera = false,
                            supportsPhysicalStream = true,
                            intrinsicZoomRatio = opticalRatio,
                            physicalCameraId = physId,
                            fovDegrees = pFov,
                            equivalent35mmFocalMm = pEq35,
                            idTypeDescription = "Logical Multi-Cam Physical $physId"
                        )
                    )
                }
            }

            // 3. Fallback for logical ultra-wide zoom (< 0.95x) if physical IDs were not advertised
            if (hasLogicalUltraWide && !detectedPhysicalUltraWide && !registeredLensesTypes.contains("BACK_ULTRAWIDE")) {
                registeredLensesTypes.add("BACK_ULTRAWIDE")
                val uwRatio = primaryBackMinZoom.coerceIn(0.35f, 0.85f)
                lenses.add(
                    LensInfo(
                        cameraId = primaryBackId,
                        facing = CameraCharacteristics.LENS_FACING_BACK,
                        lensType = LensType.ULTRAWIDE,
                        displayName = "${uwRatio}x Ultra Wide",
                        focalLengthMm = primaryMainFocal * uwRatio,
                        maxAperture = primaryMainAperture,
                        isPhysical = true,
                        isHiddenAux = false,
                        isZoomPreset = false,
                        baseZoomRatio = uwRatio,
                        minZoomRatio = primaryBackMinZoom,
                        maxZoomRatio = primaryBackMaxZoom,
                        isLogicalMultiCamera = isPrimaryBackLogical,
                        isIndependentCamera = false,
                        supportsPhysicalStream = false,
                        intrinsicZoomRatio = uwRatio,
                        fovDegrees = 110f,
                        equivalent35mmFocalMm = primaryMainEq35 * uwRatio,
                        idTypeDescription = "Logical Multi-Cam Optical Ultra-Wide"
                    )
                )
            }

            // 4. Standalone & Auxiliary Cameras (e.g. independent camera IDs like 1 for Front, 2 for Ultra-wide, 3 for Tele)
            for (id in candidateIds) {
                if (id == primaryBackId) continue
                val chars = getChars(id) ?: continue
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue

                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(4.0f)
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.8f)
                val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: SizeF(5.0f, 3.8f)
                val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val maxAperture = apertures.firstOrNull() ?: 1.8f
                val focalMm = focalLengths.firstOrNull() ?: 4.0f

                val diagMm = if (sensorSize.width > 0 && sensorSize.height > 0) {
                    kotlin.math.sqrt((sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height).toDouble()).toFloat()
                } else 6.0f
                val cropFactor = if (diagMm > 0f) 43.27f / diagMm else 6.0f
                val eq35mm = focalMm * cropFactor
                val fovDegrees = CameraOpticalCalibration.calculateHorizontalFovDegrees(sensorSize.width, focalMm)

                val isBack = facing == CameraCharacteristics.LENS_FACING_BACK
                val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT

                val lensType = when {
                    isFront -> LensType.FRONT
                    (eq35mm in 1.0f..20.0f) || focalMm <= 2.8f || fovDegrees >= 95.0f -> LensType.ULTRAWIDE
                    eq35mm >= 70f || focalMm >= 9.0f -> LensType.TELEPHOTO_3X
                    (eq35mm in 45f..70f || focalMm in 5.8f..9.0f) -> LensType.TELEPHOTO
                    minFocus > 10f && focalMm < 3.2f -> LensType.MACRO
                    else -> LensType.WIDE
                }

                val key = if (isFront) "FRONT" else "BACK_${lensType.name}"
                // If a logical physical lens for this perspective already exists, do not add duplicate standalone camera entry
                if (registeredLensesTypes.contains(key)) {
                    continue
                }

                registeredLensesTypes.add(key)
                val isOfficial = officialIds.contains(id)
                val opticalRatio = if (isFront) 1.0f else CameraOpticalCalibration.calculateCalibratedOpticalRatio(
                    lensFocalLengthMm = focalMm,
                    lensSensorWidthMm = sensorSize.width,
                    mainFocalLengthMm = primaryMainFocal,
                    mainSensorWidthMm = primaryMainSensorWidth,
                    lensType = lensType
                )

                val displayName = when (lensType) {
                    LensType.FRONT -> "Front Selfie (f/${maxAperture})"
                    LensType.ULTRAWIDE -> "${opticalRatio}x Ultra Wide (${focalMm}mm f/${maxAperture})"
                    LensType.WIDE -> "1x Wide (${focalMm}mm f/${maxAperture})"
                    LensType.TELEPHOTO -> "${opticalRatio}x Telephoto (${focalMm}mm f/${maxAperture})"
                    LensType.TELEPHOTO_3X -> "${opticalRatio}x Telephoto (${focalMm}mm f/${maxAperture})"
                    LensType.MACRO -> "Macro (${focalMm}mm)"
                }

                lenses.add(
                    LensInfo(
                        cameraId = id,
                        facing = facing,
                        lensType = lensType,
                        displayName = displayName,
                        focalLengthMm = focalMm,
                        maxAperture = maxAperture,
                        isPhysical = true,
                        isHiddenAux = !isOfficial,
                        isZoomPreset = false,
                        baseZoomRatio = opticalRatio,
                        fovDegrees = fovDegrees,
                        equivalent35mmFocalMm = eq35mm,
                        isIndependentCamera = true,
                        supportsPhysicalStream = false,
                        intrinsicZoomRatio = opticalRatio,
                        idTypeDescription = if (!isOfficial) "Aux Camera ID $id" else "Camera ID $id"
                    )
                )
            }

            // Ensure Front Camera exists
            val hasFront = lenses.any { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
            if (!hasFront) {
                lenses.add(
                    LensInfo(
                        cameraId = primaryFrontId,
                        facing = CameraCharacteristics.LENS_FACING_FRONT,
                        lensType = LensType.FRONT,
                        displayName = "Front Selfie Camera",
                        focalLengthMm = 3.5f,
                        maxAperture = 2.0f,
                        isPhysical = true,
                        isHiddenAux = false,
                        isZoomPreset = false,
                        baseZoomRatio = 1.0f,
                        fovDegrees = 85f,
                        equivalent35mmFocalMm = 22f,
                        isIndependentCamera = true,
                        supportsPhysicalStream = false,
                        intrinsicZoomRatio = 1.0f,
                        idTypeDescription = "Front Camera (1x)"
                    )
                )
            }

            // Clean sorting:
            // 1. Back Ultra-Wide (0.5x)
            // 2. Back Main Wide (1x)
            // 3. Back Telephoto (2x / 3x)
            // 4. Back Macro
            // 5. Front Selfie
            val sortedLenses = lenses.distinctBy {
                "${it.cameraId}_${it.facing}_${it.lensType.name}_${it.physicalCameraId}"
            }.sortedWith(
                compareBy<LensInfo> { it.facing }
                    .thenBy {
                        when (it.lensType) {
                            LensType.ULTRAWIDE -> 0
                            LensType.WIDE -> 1
                            LensType.TELEPHOTO -> 2
                            LensType.TELEPHOTO_3X -> 3
                            LensType.MACRO -> 4
                            LensType.FRONT -> 5
                        }
                    }
                    .thenBy { it.baseZoomRatio }
                    .thenBy { if (it.isPrimaryMain) 0 else 1 }
            )

            return CameraInventory(
                lenses = sortedLenses,
                logicalToPhysicalMap = logicalToPhysical,
                physicalToLogicalMap = physicalToLogical,
                characteristicsCache = characteristicsMap
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Error in CameraDiscovery", t)
            return CameraInventory(emptyList())
        }
    }

    /**
     * Resolves the proper lens switching strategy between current and target lens.
     */
    fun resolveSwitchStrategy(currentLens: LensInfo?, targetLens: LensInfo): LensSwitchStrategy {
        if (currentLens == null) return LensSwitchStrategy.INDEPENDENT_DEVICE

        // If target requires a different openable camera ID (e.g. Back <-> Front, or different Aux camera ID)
        if (currentLens.cameraId != targetLens.cameraId || currentLens.facing != targetLens.facing) {
            return LensSwitchStrategy.INDEPENDENT_DEVICE
        }

        // Same underlying CameraDevice:
        // Check if target lens specifies a physical camera ID inside logical multi-camera
        if (targetLens.isLogicalMultiCamera && targetLens.physicalCameraId != null && targetLens.supportsPhysicalStream) {
            return LensSwitchStrategy.LOGICAL_PHYSICAL_STREAM
        }

        return LensSwitchStrategy.LOGICAL_ZOOM
    }
}
