package com.example.camera.sound

import android.media.AudioAttributes
import android.media.MediaActionSound
import android.media.SoundPool
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.sin

/**
 * High-performance, low-latency audio manager for camera sound effects.
 * Supports rapid continuous burst shutter sound (machine-gun style) using
 * an 8-stream concurrent SoundPool dispatched asynchronously off the sensor/UI threads.
 */
object CameraSoundManager {
    private const val TAG = "CameraSoundManager"

    private var actionSound: MediaActionSound? = null
    private var burstSoundPool: SoundPool? = null
    private var burstSoundId: Int = 0
    @Volatile private var isBurstSoundLoaded: Boolean = false

    private val audioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed setting OS audio thread priority: ${t.message}")
            }
            try {
                runnable.run()
            } catch (t: Throwable) {
                Log.e(TAG, "Unhandled exception in CameraSoundWorker", t)
            }
        }, "CameraSoundWorker").apply {
            isDaemon = true
            try {
                priority = Thread.NORM_PRIORITY
            } catch (t: Throwable) {
                Log.w(TAG, "Failed setting Java thread priority: ${t.message}")
            }
        }
    }

    init {
        try {
            val sound = MediaActionSound()
            sound.load(MediaActionSound.SHUTTER_CLICK)
            sound.load(MediaActionSound.START_VIDEO_RECORDING)
            sound.load(MediaActionSound.STOP_VIDEO_RECORDING)
            actionSound = sound
        } catch (t: Throwable) {
            Log.w(TAG, "MediaActionSound initialization deferred or unsupported: ${t.message}")
        }

        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val pool = SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(attributes)
                .build()

            pool.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) {
                    burstSoundId = sampleId
                    isBurstSoundLoaded = true
                    Log.i(TAG, "Burst machine-gun shutter sound loaded successfully (id=$sampleId)")
                }
            }
            burstSoundPool = pool

            // Try loading system shutter audio; fallback to synthesized crisp mechanical click
            try {
                audioExecutor.execute {
                    try {
                        loadOrSynthesizeBurstSound(pool)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed loading/synthesizing burst sound", t)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed scheduling burst sound load", t)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed initializing burst SoundPool: ${t.message}")
        }
    }

    private fun loadOrSynthesizeBurstSound(pool: SoundPool) {
        val systemPaths = listOf(
            "/system/media/audio/ui/camera_click.ogg",
            "/system/media/audio/ui/Camera_click.ogg",
            "/system/media/audio/ui/camera_shutter.ogg",
            "/system/media/audio/ui/Shutter_01.ogg"
        )
        for (path in systemPaths) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                try {
                    pool.load(file.absolutePath, 1)
                    return
                } catch (ignored: Throwable) {}
            }
        }

        // Synthesize authentic 28ms crisp mechanical camera shutter click
        try {
            val tmpDir = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
            val wavFile = File(tmpDir, "camera_burst_click.wav")
            generateMechanicalShutterWav(wavFile)
            if (wavFile.exists()) {
                pool.load(wavFile.absolutePath, 1)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not generate synthetic burst click: ${t.message}")
        }
    }

    private fun generateMechanicalShutterWav(targetFile: File) {
        val sampleRate = 44100
        val durationMs = 28
        val numSamples = (sampleRate * durationMs) / 1000
        val pcmData = ShortArray(numSamples)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            // High transient initial curtain click (2.4kHz) + mechanical snap (1.2kHz) + decay
            val decay = kotlin.math.exp(-t * 220.0)
            val primary = sin(2.0 * Math.PI * 2400.0 * t) * 0.7
            val secondary = sin(2.0 * Math.PI * 1200.0 * t) * 0.4
            val noise = (Math.random() * 2.0 - 1.0) * 0.3 * kotlin.math.exp(-t * 350.0)
            val sample = ((primary + secondary + noise) * decay).coerceIn(-1.0, 1.0)
            pcmData[i] = (sample * 32767).toInt().toShort()
        }

        FileOutputStream(targetFile).use { fos ->
            val byteBuffer = ByteBuffer.allocate(44 + numSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
            val totalAudioLen = numSamples * 2
            val totalDataLen = totalAudioLen + 36

            // RIFF header
            byteBuffer.put("RIFF".toByteArray())
            byteBuffer.putInt(totalDataLen)
            byteBuffer.put("WAVE".toByteArray())
            byteBuffer.put("fmt ".toByteArray())
            byteBuffer.putInt(16) // Subchunk1Size (16 for PCM)
            byteBuffer.putShort(1) // AudioFormat (1 = PCM)
            byteBuffer.putShort(1) // NumChannels (1 = Mono)
            byteBuffer.putInt(sampleRate)
            byteBuffer.putInt(sampleRate * 2) // ByteRate
            byteBuffer.putShort(2) // BlockAlign
            byteBuffer.putShort(16) // BitsPerSample
            byteBuffer.put("data".toByteArray())
            byteBuffer.putInt(totalAudioLen)

            for (s in pcmData) {
                byteBuffer.putShort(s)
            }
            fos.write(byteBuffer.array())
            fos.flush()
        }
    }

    /**
     * Plays rapid machine-gun style shutter click.
     * Completely non-blocking: dispatches to a dedicated background audio thread
     * so camera acquisition and JPEG processing are never slowed down.
     * Guaranteed fail-safe: sound playback will never crash photo capture.
     */
    fun playBurstShutter() {
        try {
            audioExecutor.execute {
                try {
                    if (isBurstSoundLoaded && burstSoundId != 0) {
                        burstSoundPool?.play(burstSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
                    } else {
                        actionSound?.play(MediaActionSound.SHUTTER_CLICK)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed playing burst shutter sound: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to schedule burst shutter sound: ${t.message}")
        }
    }

    fun playShutter() {
        try {
            audioExecutor.execute {
                try {
                    actionSound?.play(MediaActionSound.SHUTTER_CLICK)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to play shutter sound: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to schedule shutter sound: ${t.message}")
        }
    }

    fun playStartVideo() {
        try {
            audioExecutor.execute {
                try {
                    actionSound?.play(MediaActionSound.START_VIDEO_RECORDING)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to play video start sound: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to schedule video start sound: ${t.message}")
        }
    }

    fun playStopVideo() {
        try {
            audioExecutor.execute {
                try {
                    actionSound?.play(MediaActionSound.STOP_VIDEO_RECORDING)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to play video stop sound: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to schedule video stop sound: ${t.message}")
        }
    }

    fun release() {
        try {
            actionSound?.release()
            actionSound = null
            burstSoundPool?.release()
            burstSoundPool = null
        } catch (ignored: Exception) {}
    }
}
