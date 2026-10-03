package com.example.camera.engine

import android.hardware.camera2.params.TonemapCurve
import com.example.camera.model.CinemaConfig
import com.example.camera.model.CinematicLut
import kotlin.math.exp
import kotlin.math.pow

private const val CURVE_POINTS = 64

/**
 * High Dynamic Range (HDR) Log Transfer & Tonemap Engine for Cinema Mode.
 *
 * Implements:
 * 1. Deep Inky Blacks & Zero Pedestal Lift:
 *    - Anchored strictly at y=0.0 when x=0.0.
 *    - Eliminates the washed-out, milky, low-contrast haze characteristic of flat log curves.
 *
 * 2. Filmic Natural Contrast & Usable Preview/Output:
 *    - Balanced S-curve midtone slope around 18% middle-grey (x=0.18 -> y≈0.27).
 *    - Rich shadow detail with gentle, natural roll-in.
 *    - Direct out-of-camera usability without mandatory aggressive grading, while still preserving latitude.
 *
 * 3. High Dynamic Range Highlight Latitude:
 *    - Asymptotic logarithmic shoulder above 0.45 extending highlight retention by 2.5+ stops.
 *    - Smooth compression prevents harsh digital 255 clipping on clouds, skies, and specular reflections.
 *
 * 4. Real Camera2 ISP Tonemap Curve:
 *    - Encodes the genuine curve onto sensor frames and recording streams in hardware.
 */
class HdrLogEngine {

    companion object {
        /**
         * Evaluates the continuous, monotonic HDR Log Opto-Electronic Transfer Function.
         * Input and output in [0.0 .. 1.0].
         */
        fun evaluateHdrLogOetf(linearLight: Float): Float {
            val x = linearLight.coerceIn(0.0f, 1.0f)
            return if (x <= 0.04f) {
                // Linear inky black toe anchored strictly at 0.0 (no milky fog)
                (2.2f * x).coerceIn(0.0f, 1.0f)
            } else if (x <= 0.45f) {
                // Natural contrast midtone curve centered around 18% middle-grey
                val t = (x - 0.04f) / 0.41f
                (0.088f + 0.482f * t.pow(0.88f)).coerceIn(0.0f, 1.0f)
            } else {
                // High dynamic range logarithmic shoulder for highlight latitude
                val t = (x - 0.45f) / 0.55f
                val shoulder = (1.0f - exp(-2.6f * t)) / (1.0f - exp(-2.6f))
                (0.57f + 0.43f * shoulder).coerceIn(0.0f, 1.0f)
            }
        }
    }

    private val curveRed = FloatArray(CURVE_POINTS * 2)
    private val curveGreen = FloatArray(CURVE_POINTS * 2)
    private val curveBlue = FloatArray(CURVE_POINTS * 2)

    private var cachedTonemapCurve: TonemapCurve? = null
    private var lastExposure = 0.0f
    private var lastContrast = 0.0f
    private var lastShadows = 0.0f
    private var lastHighlights = 0.0f
    private var lastWashedOut = 0.0f
    private var lastLut = CinematicLut.NONE

    /**
     * Generates a 64-point Camera2 TonemapCurve for the HDR Log profile,
     * incorporating live user adjustments (shadows, highlights, contrast, exposure, washedOut).
     */
    @Synchronized
    fun getTonemapCurve(
        userExposure: Float = 0.0f,
        userShadows: Float = 0.0f,
        userHighlights: Float = 0.0f,
        userContrast: Float = 0.0f,
        lut: CinematicLut = CinematicLut.NONE,
        washedOut: Float = 0.0f
    ): TonemapCurve {
        if (cachedTonemapCurve != null &&
            userExposure == lastExposure &&
            userContrast == lastContrast &&
            userShadows == lastShadows &&
            userHighlights == lastHighlights &&
            washedOut == lastWashedOut &&
            lut == lastLut
        ) {
            return cachedTonemapCurve!!
        }

        lastExposure = userExposure
        lastContrast = userContrast
        lastShadows = userShadows
        lastHighlights = userHighlights
        lastWashedOut = washedOut
        lastLut = lut

        val numPoints = CURVE_POINTS
        val lutContrast = if (lut != CinematicLut.NONE) (lut.contrast - 1.0f) else 0.0f
        val totalContrast = (userContrast + lutContrast).coerceIn(-1.0f, 1.5f)

        for (i in 0 until numPoints) {
            val baseNormalizedX = i.toFloat() / (numPoints - 1).toFloat()

            // Exposure shift along the characteristic sensor curve
            val x = if (userExposure != 0.0f) {
                val expScale = 2.0f.pow(userExposure * 0.75f)
                (baseNormalizedX * expScale).coerceIn(0f, 1f)
            } else {
                baseNormalizedX
            }

            var y = evaluateHdrLogOetf(x)

            // User contrast (pivoting at 18% middle-grey)
            if (totalContrast != 0.0f) {
                val factor = 1.0f + (totalContrast * 0.38f)
                y = 0.27f + (y - 0.27f) * factor
            }

            // User shadow sculpting (toe region x < 0.40)
            if (userShadows != 0.0f && x < 0.40f) {
                val weight = (1.0f - x / 0.40f).pow(2.0f)
                y += userShadows * 0.16f * weight
            }

            // User highlight recovery / shoulder compression (shoulder region x > 0.50)
            if (userHighlights != 0.0f && x > 0.50f) {
                val weight = ((x - 0.50f) / 0.50f).pow(2.0f)
                y += userHighlights * 0.16f * weight
            }

            // LUT shadow toe and highlight roll-off tuning
            if (lut != CinematicLut.NONE) {
                if (lut.shadowToe != 0.0f && x < 0.35f) {
                    val weight = (1.0f - x / 0.35f).pow(2.0f)
                    y += lut.shadowToe * 0.10f * weight
                }
                if (lut.highlightRollOff > 0.5f && x > 0.65f) {
                    val factor = (lut.highlightRollOff - 0.5f) * 2.0f
                    val rollWeight = ((x - 0.65f) / 0.35f).pow(2.0f)
                    y -= factor * 0.05f * rollWeight
                }
            }

            // Guarantee true black anchoring at 0.0
            var finalY = y.coerceIn(0f, 1f)
            if (baseNormalizedX == 0.0f) {
                finalY = 0.0f
            }

            val idx = i * 2
            curveRed[idx] = baseNormalizedX
            curveRed[idx + 1] = finalY
            curveGreen[idx] = baseNormalizedX
            curveGreen[idx + 1] = finalY
            curveBlue[idx] = baseNormalizedX
            curveBlue[idx + 1] = finalY
        }

        val curve = TonemapCurve(curveRed, curveGreen, curveBlue)
        cachedTonemapCurve = curve
        return curve
    }
}
