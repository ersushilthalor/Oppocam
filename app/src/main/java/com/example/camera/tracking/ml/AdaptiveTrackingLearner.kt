package com.example.camera.tracking.ml

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.camera.tracking.model.NormalizedRect
import com.example.camera.tracking.model.TrackedSubject
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Local Adaptive Learning Engine for AI Subject Tracking.
 *
 * Capabilities:
 * 1. Locally learns appearance (color signature, aspect ratio, proportions) of user-selected
 *    and verified subjects.
 * 2. Improves future auto-acquisition and reacquisition by boosting matching confidence for
 *    previously confirmed subjects.
 * 3. STRICT NEGATIVE LEARNING FILTER:
 *    - Rejects wrong selections (cancelled or re-tapped within < 1.5s).
 *    - Rejects static objects, furniture, and walls (zero or negligible motion).
 *    - Rejects objects explicitly marked non-human without genuine continuous movement.
 * 4. Completely local, privacy-preserving, persisted via SharedPreferences JSON.
 */
class AdaptiveTrackingLearner(context: Context) {

    companion object {
        private const val TAG = "AdaptiveLearner"
        private const val PREFS_NAME = "ai_tracking_adaptive_learner"
        private const val KEY_PROFILES = "learned_profiles_json"
        private const val MAX_PROFILES = 8
        private const val MIN_TRACK_DURATION_FOR_LEARNING_MS = 1500L
        private const val MIN_GENUINE_MOTION_DISPLACEMENT = 0.025f
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class LearnedProfile(
        val id: String,
        val label: String,
        val colorSignature: FloatArray,
        val aspectRatio: Float,
        val relativeArea: Float,
        val isHuman: Boolean,
        var selectionCount: Int = 1,
        var lastLearnedTimestamp: Long = System.currentTimeMillis()
    )

    private val learnedProfiles = mutableListOf<LearnedProfile>()

    // Pending session state to prevent learning wrong taps or static objects
    private var pendingSessionId: Int? = null
    private var pendingSessionStartTime: Long = 0L
    private var pendingStartCenterX: Float = 0f
    private var pendingStartCenterY: Float = 0f
    private var pendingMaxDisplacement: Float = 0f
    private var pendingSignature: FloatArray? = null
    private var pendingBounds: NormalizedRect? = null
    private var pendingIsHuman: Boolean = false
    private var pendingLabel: String = "Subject"

    init {
        loadProfilesFromDisk()
    }

    @Synchronized
    fun getLearnedProfilesCount(): Int = learnedProfiles.size

    /**
     * Records the start of a user or auto-selection session.
     * Evaluation is deferred until the session is completed or confirmed.
     */
    @Synchronized
    fun startSelectionSession(
        subject: TrackedSubject,
        colorSignature: FloatArray?,
        isHuman: Boolean
    ) {
        pendingSessionId = subject.trackingId
        pendingSessionStartTime = System.currentTimeMillis()
        pendingStartCenterX = subject.bounds.centerX
        pendingStartCenterY = subject.bounds.centerY
        pendingMaxDisplacement = 0f
        pendingSignature = colorSignature?.clone()
        pendingBounds = subject.bounds
        pendingIsHuman = isHuman
        pendingLabel = subject.label
    }

    /**
     * Updates movement telemetry during active tracking.
     */
    @Synchronized
    fun updateTrackingTelemetry(currentBounds: NormalizedRect) {
        if (pendingSessionId == null) return
        val dx = currentBounds.centerX - pendingStartCenterX
        val dy = currentBounds.centerY - pendingStartCenterY
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > pendingMaxDisplacement) {
            pendingMaxDisplacement = dist
        }
        pendingBounds = currentBounds
    }

    /**
     * Called when tracking of a subject ends (e.g. unlock, switch, lost, or photo/video capture).
     * Validates whether this was a genuine confirmed subject or a wrong/static selection.
     */
    @Synchronized
    fun endSelectionSession(wasExplicitCorrection: Boolean) {
        val id = pendingSessionId ?: return
        val duration = System.currentTimeMillis() - pendingSessionStartTime
        val sig = pendingSignature
        val bounds = pendingBounds
        val isHuman = pendingIsHuman
        val maxDisp = pendingMaxDisplacement

        // Clear pending session
        pendingSessionId = null

        // STRICT FILTER 1: If user quickly corrected or cancelled within 1.5s -> WRONG SELECTION, DO NOT LEARN
        if (wasExplicitCorrection || duration < MIN_TRACK_DURATION_FOR_LEARNING_MS) {
            Log.d(TAG, "Rejected learning for subject $id: session too brief (${duration}ms) or corrected")
            return
        }

        // STRICT FILTER 2: If object is not human AND has zero or negligible motion -> STATIC OBJECT, DO NOT LEARN
        if (!isHuman && maxDisp < MIN_GENUINE_MOTION_DISPLACEMENT) {
            Log.d(TAG, "Rejected learning for subject $id: static non-human object (disp=$maxDisp)")
            return
        }

        // Subject verified! Learn profile.
        if (sig != null && bounds != null && bounds.width > 0.03f && bounds.height > 0.03f) {
            val aspect = bounds.height / bounds.width
            val area = bounds.width * bounds.height
            learnSubject(
                label = pendingLabel,
                signature = sig,
                aspectRatio = aspect,
                relativeArea = area,
                isHuman = isHuman
            )
        }
    }

    /**
     * Incorporates a verified subject into local adaptive memory.
     */
    @Synchronized
    private fun learnSubject(
        label: String,
        signature: FloatArray,
        aspectRatio: Float,
        relativeArea: Float,
        isHuman: Boolean
    ) {
        // Check if an existing profile matches this appearance closely
        val existing = learnedProfiles.firstOrNull { prof ->
            val colorSim = compareSignatures(prof.colorSignature, signature)
            val aspectSim = 1f - (abs(prof.aspectRatio - aspectRatio) / prof.aspectRatio.coerceAtLeast(0.1f)).coerceIn(0f, 1f)
            colorSim > 0.72f && aspectSim > 0.65f
        }

        if (existing != null) {
            // Update existing profile using exponential moving average
            val alpha = 0.35f
            for (i in existing.colorSignature.indices) {
                if (i < signature.size) {
                    existing.colorSignature[i] = existing.colorSignature[i] * (1f - alpha) + signature[i] * alpha
                }
            }
            existing.selectionCount++
            existing.lastLearnedTimestamp = System.currentTimeMillis()
            Log.d(TAG, "Updated existing learned profile ${existing.id} (count=${existing.selectionCount})")
        } else {
            // Add new profile
            val newProfile = LearnedProfile(
                id = "prof_${System.currentTimeMillis()}",
                label = label,
                colorSignature = signature.clone(),
                aspectRatio = aspectRatio,
                relativeArea = relativeArea,
                isHuman = isHuman,
                selectionCount = 1,
                lastLearnedTimestamp = System.currentTimeMillis()
            )
            learnedProfiles.add(0, newProfile)

            // Keep only top MAX_PROFILES (prune least recently used)
            if (learnedProfiles.size > MAX_PROFILES) {
                learnedProfiles.sortByDescending { it.selectionCount * 1000000L + it.lastLearnedTimestamp }
                while (learnedProfiles.size > MAX_PROFILES) {
                    learnedProfiles.removeAt(learnedProfiles.lastIndex)
                }
            }
            Log.d(TAG, "Learned new subject profile: label=$label, isHuman=$isHuman (total=${learnedProfiles.size})")
        }

        saveProfilesToDisk()
    }

    /**
     * Computes adaptive affinity score [0..1] for a candidate detection against learned memory.
     * Higher score indicates candidate matches a previously confirmed, user-preferred subject.
     */
    @Synchronized
    fun computeLearnedAffinity(
        bounds: NormalizedRect,
        signature: FloatArray?,
        isHuman: Boolean
    ): Float {
        if (learnedProfiles.isEmpty() || signature == null || signature.isEmpty()) {
            return if (isHuman) 0.35f else 0.15f
        }

        val candAspect = bounds.height / bounds.width
        val candArea = bounds.width * bounds.height
        var maxAffinity = 0f

        for (prof in learnedProfiles) {
            val colorSim = compareSignatures(prof.colorSignature, signature)
            val aspectDiff = abs(prof.aspectRatio - candAspect) / prof.aspectRatio.coerceAtLeast(0.1f)
            val aspectSim = (1f - aspectDiff).coerceIn(0f, 1f)
            val areaDiff = abs(prof.relativeArea - candArea) / prof.relativeArea.coerceAtLeast(0.01f)
            val areaSim = (1f - areaDiff).coerceIn(0f, 1f)

            val humanBonus = if (prof.isHuman && isHuman) 0.2f else if (prof.isHuman != isHuman) -0.2f else 0f
            val countBonus = min(prof.selectionCount * 0.05f, 0.25f)

            val affinity = (colorSim * 0.55f + aspectSim * 0.25f + areaSim * 0.20f + humanBonus + countBonus).coerceIn(0f, 1f)
            if (affinity > maxAffinity) {
                maxAffinity = affinity
            }
        }

        return maxAffinity
    }

    /**
     * Computes Bhattacharyya distance similarity coefficient [0..1] between two signatures.
     */
    private fun compareSignatures(sigA: FloatArray, sigB: FloatArray): Float {
        if (sigA.isEmpty() || sigB.isEmpty()) return 0f
        var sum = 0f
        val len = min(sigA.size, sigB.size)
        for (i in 0 until len) {
            sum += sqrt((sigA[i] * sigB[i]).coerceAtLeast(0f))
        }
        return sum.coerceIn(0f, 1f)
    }

    @Synchronized
    fun clearLearnedProfiles() {
        learnedProfiles.clear()
        prefs.edit().remove(KEY_PROFILES).apply()
        Log.d(TAG, "Cleared all learned profiles")
    }

    private fun saveProfilesToDisk() {
        try {
            val jsonArray = JSONArray()
            for (prof in learnedProfiles) {
                val obj = JSONObject().apply {
                    put("id", prof.id)
                    put("label", prof.label)
                    put("aspectRatio", prof.aspectRatio.toDouble())
                    put("relativeArea", prof.relativeArea.toDouble())
                    put("isHuman", prof.isHuman)
                    put("selectionCount", prof.selectionCount)
                    put("lastLearnedTimestamp", prof.lastLearnedTimestamp)

                    val sigArr = JSONArray()
                    for (v in prof.colorSignature) {
                        sigArr.put(v.toDouble())
                    }
                    put("signature", sigArr)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_PROFILES, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist learned profiles", e)
        }
    }

    private fun loadProfilesFromDisk() {
        try {
            val jsonStr = prefs.getString(KEY_PROFILES, null) ?: return
            val jsonArray = JSONArray(jsonStr)
            learnedProfiles.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val label = obj.optString("label", "Subject")
                val aspect = obj.getDouble("aspectRatio").toFloat()
                val area = obj.getDouble("relativeArea").toFloat()
                val isHuman = obj.optBoolean("isHuman", false)
                val count = obj.optInt("selectionCount", 1)
                val time = obj.optLong("lastLearnedTimestamp", System.currentTimeMillis())

                val sigArr = obj.getJSONArray("signature")
                val sig = FloatArray(sigArr.length())
                for (s in 0 until sigArr.length()) {
                    sig[s] = sigArr.getDouble(s).toFloat()
                }

                learnedProfiles.add(
                    LearnedProfile(
                        id = id,
                        label = label,
                        colorSignature = sig,
                        aspectRatio = aspect,
                        relativeArea = area,
                        isHuman = isHuman,
                        selectionCount = count,
                        lastLearnedTimestamp = time
                    )
                )
            }
            Log.d(TAG, "Loaded ${learnedProfiles.size} learned profiles from storage")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load learned profiles", e)
        }
    }
}
